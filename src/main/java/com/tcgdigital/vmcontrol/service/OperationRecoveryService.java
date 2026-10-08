package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationDetail;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.repository.OperationDetailRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fails executions left PENDING/IN_PROGRESS by a restart or a dead worker, so they no longer
 * block their environment with "already in progress" (H12). Runs at startup and lazily before
 * every new operation; a run that is still heartbeating (on any node) is never touched.
 */
@Service
public class OperationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OperationRecoveryService.class);

    private static final List<String> ACTIVE = List.of(
            OperationExecution.statusValue(ExecutionStatus.PENDING),
            OperationExecution.statusValue(ExecutionStatus.IN_PROGRESS));

    private final OperationExecutionRepository executionRepository;
    private final OperationDetailRepository detailRepository;
    private final AuditService auditService;
    private final ScheduledJobLockService jobLockService;
    private final long staleAfterMinutes;
    private final boolean recoveryEnabled;

    public OperationRecoveryService(OperationExecutionRepository executionRepository,
                                    OperationDetailRepository detailRepository,
                                    AuditService auditService,
                                    ScheduledJobLockService jobLockService,
                                    @Value("${vm.operations.stale-after-minutes:30}") long staleAfterMinutes,
                                    @Value("${vm.operations.recovery.enabled:true}") boolean recoveryEnabled) {
        this.executionRepository = executionRepository;
        this.detailRepository = detailRepository;
        this.auditService = auditService;
        this.jobLockService = jobLockService;
        this.staleAfterMinutes = staleAfterMinutes;
        this.recoveryEnabled = recoveryEnabled;
    }

    /** This node's executor id ("hostname-pid"), written on executions it runs. */
    public String executorId() {
        return jobLockService.getOwnerId();
    }

    /** At startup: fail this host's runs from a previous process, then every stale run. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!recoveryEnabled) {
            return;
        }
        int interrupted = failInterruptedOnThisHost();
        int stale = failStale(null);
        if (interrupted + stale > 0) {
            log.warn("Startup recovery failed {} interrupted and {} stale operation(s)", interrupted, stale);
        }
    }

    /**
     * Fail active executions (of one environment, or all when null) with no heartbeat for
     * vm.operations.stale-after-minutes. In its own transaction so the caller's persistence
     * context is untouched.
     *
     * @return how many were failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failStale(String environmentIdOrNull) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(staleAfterMinutes));
        int failed = 0;
        for (OperationExecution execution : executionRepository.findActiveWithEnvironment(environmentIdOrNull)) {
            Timestamp last = execution.getLastHeartbeatAt() != null ? execution.getLastHeartbeatAt() : execution.getStartedAt();
            if (last != null && last.toInstant().isBefore(cutoff)) {
                if (fail(execution, "Interrupted: no progress for " + staleAfterMinutes
                        + " minutes (application restart?)")) {
                    failed++;
                }
            }
        }
        return failed;
    }

    /** Runs this host started under another pid are dead whatever their age: this process restarted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failInterruptedOnThisHost() {
        String self = executorId();
        int dash = self.lastIndexOf('-');
        if (dash <= 0) {
            return 0;
        }
        String hostPrefix = self.substring(0, dash + 1);
        int failed = 0;
        for (OperationExecution execution : executionRepository.findActiveWithEnvironment(null)) {
            String executor = execution.getExecutorId();
            if (executor != null && executor.startsWith(hostPrefix) && !executor.equals(self)
                    && isPid(executor.substring(hostPrefix.length()))) {
                if (fail(execution, "Interrupted: the application restarted while this operation was running")) {
                    failed++;
                }
            }
        }
        return failed;
    }

    private static boolean isPid(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private boolean fail(OperationExecution execution, String message) {
        String executionId = execution.getExecutionId();
        if (executionRepository.transitionStatus(executionId, ACTIVE,
                OperationExecution.statusValue(ExecutionStatus.FAILED), Timestamp.from(Instant.now()), message) == 0) {
            return false; // finished or cancelled meanwhile
        }
        int steps = 0;
        for (String status : List.of("pending", "in_progress")) {
            for (OperationDetail detail : detailRepository
                    .findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(executionId, status)) {
                detail.setStatus("failed");
                detail.setErrorMessage(message);
                detail.setCompletedAt(Timestamp.from(Instant.now()));
                detailRepository.save(detail);
                steps++;
            }
        }
        if (steps > 0) {
            executionRepository.incrementCounters(executionId, 0, steps, 0);
        }
        log.warn("Operation {} in environment {} failed by recovery: {}", executionId,
                execution.getEnvironment().getEnvironmentId(), message);
        try {
            auditService.logOperationFailed(execution.getInitiatedByUserId(),
                    execution.getEnvironment().getEnvironmentId(), execution.getEnvironment().getName(),
                    executionId, execution.getOperationType().name(), message);
        } catch (Exception e) {
            log.warn("Could not audit recovered operation {}: {}", executionId, e.getMessage());
        }
        return true;
    }
}
