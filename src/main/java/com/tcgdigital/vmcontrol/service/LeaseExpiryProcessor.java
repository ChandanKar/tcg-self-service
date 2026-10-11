package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.LeaseStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.repository.LeaseExtensionRequestRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Ends one overdue lease (E15-T05) in its own transaction: releases the environment's lock and
 * stops whatever still runs, dependents before their dependencies, as a system operation
 * attributed to "Lease expired". While another operation runs the lease is retried on the next
 * sweep; after {@link #MAX_STOP_ATTEMPTS} attempts it is marked expired without stopping and the
 * owners are told. No cloud call happens unless leases are on for the environment.
 */
@Service
public class LeaseExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryProcessor.class);

    static final int MAX_STOP_ATTEMPTS = 10;
    static final String TRIGGER = "Lease expired";

    private final EnvironmentLeaseRepository leaseRepository;
    private final EnvironmentLeaseParticipantRepository participantRepository;
    private final LeaseExtensionRequestRepository extensionRepository;
    private final OperationExecutionRepository executionRepository;
    private final VmRepository vmRepository;
    private final LeaseService leaseService;
    private final LockService lockService;
    private final VmOperationsService operationsService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AfterCommit afterCommit;

    public LeaseExpiryProcessor(EnvironmentLeaseRepository leaseRepository,
                                EnvironmentLeaseParticipantRepository participantRepository,
                                LeaseExtensionRequestRepository extensionRepository,
                                OperationExecutionRepository executionRepository,
                                VmRepository vmRepository,
                                LeaseService leaseService,
                                @Lazy LockService lockService,
                                @Lazy VmOperationsService operationsService,
                                AuditService auditService,
                                @Lazy NotificationService notificationService,
                                AfterCommit afterCommit) {
        this.leaseRepository = leaseRepository;
        this.participantRepository = participantRepository;
        this.extensionRepository = extensionRepository;
        this.executionRepository = executionRepository;
        this.vmRepository = vmRepository;
        this.leaseService = leaseService;
        this.lockService = lockService;
        this.operationsService = operationsService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.afterCommit = afterCommit;
    }

    /**
     * @return true if the lease was ended (stopped, or given up after too many attempts)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(String leaseId) {
        EnvironmentLease lease = leaseRepository.findById(leaseId).orElse(null);
        Instant now = Instant.now();
        if (lease == null || lease.getStatus() != LeaseStatus.ACTIVE || lease.getEndsAt().toInstant().isAfter(now)) {
            return false;
        }
        Environment env = lease.getEnvironment();
        String envId = env.getEnvironmentId();
        String envName = env.getName();
        List<String> participantIds = participantRepository
                .findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(leaseId).stream()
                .map(EnvironmentLeaseParticipant::getUserId).toList();

        if (!leaseService.isEnabledFor(env)) {
            // Leases were switched off for this environment after it started: end it, stop nothing.
            finish(lease, now, "Leases turned off for the environment", null);
            return true;
        }

        if (executionRepository.hasActiveOperations(envId)) {
            lease.setStopAttempts(lease.getStopAttempts() + 1);
            if (lease.getStopAttempts() < MAX_STOP_ATTEMPTS) {
                leaseRepository.save(lease);
                log.info("Lease {} on {} is overdue but an operation is running; retrying next sweep", leaseId, envName);
                return false;
            }
            finish(lease, now, "Could not stop: operation in progress", null);
            auditService.logLeaseExpired(envId, envName, leaseId,
                    "Lease expired but the environment was not stopped: an operation was still in progress after "
                            + MAX_STOP_ATTEMPTS + " attempts.");
            afterCommit.run(() -> notificationService.notifyLeaseExpiredForEnvironment(envId, envName, participantIds,
                    "lease expired, but it could not be stopped because an operation was still running."));
            return true;
        }

        lockService.releaseForSystem(envId, "lease expired");

        String executionId = null;
        boolean anyRunning = vmRepository.findByEnvironmentId(envId).stream()
                .anyMatch(vm -> vm.getStatus() == VmStatus.RUNNING || vm.getStatus() == VmStatus.STARTING);
        if (anyRunning) {
            StartOperationDTO stop = new StartOperationDTO();
            stop.setOperationType(OperationType.STOP);
            stop.setSkipAlreadyInTargetState(true);
            stop.setContinueOnFailure(true);
            executionId = operationsService.startSystemOperation(envId, lease.getStartedByUserId(), stop, TRIGGER,
                            com.tcgdigital.vmcontrol.model.OperationTriggerSource.LEASE)
                    .map(OperationExecution::getExecutionId).orElse(null);
        }
        finish(lease, now, TRIGGER, executionId);
        auditService.logLeaseExpired(envId, envName, leaseId, executionId == null
                ? "Lease expired; nothing was running."
                : "Lease expired; stopping the environment (operation " + executionId + ").");
        String detail = executionId == null
                ? "lease expired. Start it again from the environment page."
                : "lease expired - stopping. Start it again from the environment page.";
        afterCommit.run(() -> notificationService.notifyLeaseExpiredForEnvironment(envId, envName, participantIds, detail));
        log.info("Lease {} on {} expired; stop execution {}", leaseId, envName, executionId);
        return true;
    }

    private void finish(EnvironmentLease lease, Instant now, String reason, String executionId) {
        lease.setStatus(LeaseStatus.EXPIRED);
        lease.setEndedAt(Timestamp.from(now));
        lease.setEndReason(reason);
        lease.setStopExecutionId(executionId);
        leaseRepository.save(lease);
        extensionRepository.expirePendingForLease(lease.getLeaseId());
    }
}
