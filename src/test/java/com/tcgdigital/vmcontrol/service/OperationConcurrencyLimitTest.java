package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * One execution cannot hold every operation thread (E05-T07, M10). Not @Transactional.
 */
class OperationConcurrencyLimitTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operationsService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired @Qualifier("eksOperationExecutor") private Executor eksOperationExecutor;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final Map<String, Instant> callStartedAt = new ConcurrentHashMap<>();

    @AfterEach
    void restoreDefaultLimit() {
        setLimit(5);
    }

    private void setLimit(int limit) {
        Object target = AopTestUtils.getTargetObject(operationsService); // Object: pick the instance overload
        ReflectionTestUtils.setField(target, "perExecutionParallelism", limit);
    }

    /** startVm sleeps {@code millis} and records concurrency and when each VM's call began. */
    private void slowStarts(long millis) {
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenAnswer(inv -> {
            callStartedAt.put(inv.getArgument(0), Instant.now());
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(millis);
            } finally {
                inFlight.decrementAndGet();
            }
            return CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING));
        });
    }

    private Environment environmentWithVms(String name, int count, User user) {
        Environment env = newEnvironment(name);
        VmGroup group = newGroup(env, "g");
        for (int i = 0; i < count; i++) {
            newVm(group, "vm" + i, VmStatus.STOPPED);
        }
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        return env;
    }

    private String startAll(Environment env, User user) {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);
        return operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto).getExecutionId();
    }

    private OperationExecution awaitDone(String executionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            OperationExecution e = executions.findById(executionId).orElseThrow();
            if (e.getStatus() == ExecutionStatus.COMPLETED || e.getStatus() == ExecutionStatus.FAILED
                    || e.getStatus() == ExecutionStatus.PARTIAL_SUCCESS) {
                return e;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("execution did not finish");
    }

    @Test
    void oneExecutionRunsAtMostItsShareOfStepsAtOnce() throws Exception {
        setLimit(2);
        User user = newUser("limit@example.com", false, false);
        Environment env = environmentWithVms("Limit", 6, user);
        slowStarts(150);

        OperationExecution done = awaitDone(startAll(env, user));

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(done.getCompletedTargets()).isEqualTo(6);
        assertThat(maxInFlight.get()).isEqualTo(2);
    }

    @Test
    void aSingleVmStartElsewhereIsNotQueuedBehindABigRun() throws Exception {
        User user = newUser("big-run@example.com", false, false);
        Environment big = environmentWithVms("Big", 24, user);
        Environment small = environmentWithVms("Small", 1, user);
        Vm smallVm = vmRepository.findByEnvironmentId(small.getEnvironmentId()).get(0);
        slowStarts(1500);

        String bigRun = startAll(big, user);
        Thread.sleep(300); // the big run is now holding its share of threads
        Instant smallRequested = Instant.now();
        String smallRun = startAll(small, user);

        awaitDone(smallRun);
        Duration waited = Duration.between(smallRequested, callStartedAt.get(smallVm.getProviderVmId()));
        assertThat(waited).isLessThan(Duration.ofMillis(1000)); // well under one 1.5 s step of the big run
        awaitDone(bigRun);
    }

    @Test
    void eksWorkRunsOnItsOwnNamedPool() throws Exception {
        CompletableFuture<String> thread = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(),
                eksOperationExecutor);

        assertThat(thread.get()).startsWith("eks-op-");
    }
}
