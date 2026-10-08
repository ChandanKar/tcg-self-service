package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.OperationEstimateDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.OperationCancelledException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Service for orchestrating VM operations across an environment.
 * Handles dependency ordering, batch execution, and status tracking.
 */
@Service
public class VmOperationsService {

    private static final Logger log = LoggerFactory.getLogger(VmOperationsService.class);

    /** Stored status values of a run that can still be cancelled or fail. */
    private static final List<String> ACTIVE_STATUSES = List.of(
            OperationExecution.statusValue(ExecutionStatus.PENDING),
            OperationExecution.statusValue(ExecutionStatus.IN_PROGRESS));

    private final OperationExecutionRepository executionRepository;
    private final OperationDetailRepository detailRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;
    private final DependencyValidator dependencyValidator;
    private final CloudProviderFactory cloudProviderFactory;
    private final LockService lockService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final StateSyncService stateSyncService;
    private final Executor vmOperationExecutor;

    // Self-reference via proxy so @Async is properly applied (self-invocation bypasses Spring proxy)
    @Lazy
    @Autowired
    private VmOperationsService self;

    // @Lazy to break the construction cycle VmOperationsService -> SecurityService ->
    // EnvironmentAccessService -> AutomationRuleService -> VmOperationsService.
    @Lazy
    @Autowired
    private SecurityService securityService;

    public VmOperationsService(OperationExecutionRepository executionRepository,
                               OperationDetailRepository detailRepository,
                               EnvironmentRepository environmentRepository,
                               VmGroupRepository groupRepository,
                               VmRepository vmRepository,
                               DependencyValidator dependencyValidator,
                               CloudProviderFactory cloudProviderFactory,
                               LockService lockService,
                               ObjectMapper objectMapper,
                               AuditService auditService,
                               NotificationService notificationService,
                               StateSyncService stateSyncService,
                               @Qualifier("vmOperationExecutor") Executor vmOperationExecutor) {
        this.executionRepository = executionRepository;
        this.detailRepository = detailRepository;
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.dependencyValidator = dependencyValidator;
        this.cloudProviderFactory = cloudProviderFactory;
        this.lockService = lockService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.stateSyncService = stateSyncService;
        this.vmOperationExecutor = vmOperationExecutor;
    }

    /**
     * Start an operation on an environment.
     * Creates execution plan based on dependencies and starts async execution.
     */
    @Transactional
    public OperationExecution startOperation(String environmentId, String userId, StartOperationDTO dto) {
        // Verify environment exists
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));

        // Verify user has lock on environment
        lockService.verifyLockPermission(environmentId, userId);

        // Check for existing active operations
        if (executionRepository.hasActiveOperations(environmentId)) {
            throw new ValidationException("An operation is already in progress for this environment");
        }

        // Get target VMs based on request, ordered so dependencies always precede dependents
        List<Vm> targetVms = resolveTargetVms(environment, dto);

        if (targetVms.isEmpty()) {
            throw new ValidationException("No VMs to operate on");
        }

        // Group-scoped access: a user without environment-wide USER can only operate the groups
        // they hold a grant on. An explicit group/VM target they can't touch fails the request;
        // a whole-environment request is narrowed to the groups they can operate.
        targetVms = enforceGroupScopeAccess(targetVms, dto, userId);

        // A dependency outside this run's scope is never started here, so nothing will ever
        // wait on it — verify it's actually running now rather than assuming it based on order.
        // Not relevant for a pure STOP, which doesn't require its dependency to be up.
        if (dto.getOperationType() != OperationType.STOP) {
            dependencyValidator.validateLiveDependencies(targetVms);
        }

        List<PlannedStep> plan = planSteps(targetVms, dto.getOperationType());

        // Create execution record
        OperationExecution execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(environment);
        execution.setOperationType(dto.getOperationType());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setInitiatedByUserId(userId);
        execution.setTotalTargets(plan.size());

        execution = executionRepository.save(execution);

        // One detail per planned step, recording which earlier details (by detailId) must
        // complete successfully before it may be attempted.
        int sequencePosition = 0;
        Map<String, String> detailIdByStepKey = new HashMap<>();

        for (PlannedStep step : plan) {
            OperationDetail detail = new OperationDetail();
            detail.setDetailId(UUID.randomUUID().toString());
            detail.setExecution(execution);
            detail.setTargetType("vm");
            detail.setTargetId(step.vm().getVmId());
            detail.setTargetName(step.vm().getName());
            detail.setAction(step.action());
            detail.setStatus("pending");
            detail.setSequencePosition(++sequencePosition);

            List<String> depDetailIds = step.dependsOnKeys().stream()
                    .map(detailIdByStepKey::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (!depDetailIds.isEmpty()) {
                detail.setDependsOnDetailIds(writeJson(depDetailIds));
            }

            detailRepository.save(detail);
            detailIdByStepKey.put(step.key(), detail.getDetailId());
        }
        int vmCount = targetVms.size();

        log.info("Created operation execution {} for environment {} ({} VMs, {} steps)",
                execution.getExecutionId(), environmentId, vmCount, plan.size());

        // Audit logging
        auditService.logOperationStarted(userId, environmentId, environment.getName(),
                execution.getExecutionId(), dto.getOperationType().name());

        runSideEffect("notify requested operation", execution.getExecutionId(), () ->
                notificationService.notifyOperationRequestedForEnvironment(
                        environmentId,
                        environment.getName(),
                        userId,
                        dto.getOperationType().name(),
                        operationScopeLabel(dto),
                        vmCount
                ));

        // Fire async execution after this transaction commits so the execution row is visible
        final String executionId = execution.getExecutionId();
        final boolean continueOnFailure = dto.isContinueOnFailure();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.executeOperationAsync(executionId, continueOnFailure);
            }
        });

        return execution;
    }

    /**
     * Get execution status.
     */
    public OperationExecution getExecution(String executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("OperationExecution", executionId));
    }

    /**
     * An execution that belongs to the path environment; 404 for an unknown id or one from
     * another environment, so ids from elsewhere are neither used nor confirmed (H11).
     */
    public OperationExecution getExecutionForEnvironment(String environmentId, String executionId) {
        OperationExecution execution = executionRepository.findByIdWithEnvironment(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("OperationExecution", executionId));
        securityService.assertSameEnvironment(execution.getEnvironment().getEnvironmentId(), environmentId);
        return execution;
    }

    /**
     * Get execution with details, scoped to the path environment.
     */
    @Transactional(readOnly = true)
    public OperationExecution getExecutionWithDetails(String environmentId, String executionId) {
        OperationExecution execution = getExecutionForEnvironment(environmentId, executionId);
        // Force load details
        execution.getDetails().size();
        return execution;
    }

    /**
     * Get recent executions for an environment.
     */
    public List<OperationExecution> getRecentExecutions(String environmentId) {
        return executionRepository.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(environmentId);
    }

    /**
     * Build average-time estimates for an operation, scoped to environment / group / VM.
     * Uses last 10 completed executions for the environment as the sample window.
     * All duration arithmetic is done in Java to stay DB-dialect-agnostic.
     *
     * @param environmentId always required
     * @param operationType "start" or "stop"
     * @param groupId       optional — scope to a single group
     * @param vmId          optional — scope to a single VM (takes precedence over groupId)
     */
    @Transactional(readOnly = true)
    public OperationEstimateDTO getOperationEstimate(String environmentId, String operationType,
                                                     String groupId, String vmId) {
        OperationEstimateDTO estimate = new OperationEstimateDTO();
        estimate.setOperationType(operationType.toUpperCase());

        // --- Determine scope ---
        List<String> scopedVmIds = null; // null = all VMs in environment

        if (vmId != null && !vmId.isBlank()) {
            // VM scope — the VM must belong to this environment (404 otherwise, H1)
            Vm vm = vmInEnvironment(environmentId, vmId);
            scopedVmIds = List.of(vmId);
            estimate.setScopeLevel("VM");
            estimate.setScopeId(vmId);
            estimate.setScopeName(vm.getName());
        } else if (groupId != null && !groupId.isBlank()) {
            // Group scope — the group must belong to this environment (404 otherwise, H1)
            VmGroup group = groupInEnvironment(environmentId, groupId);
            List<Vm> groupVms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId);
            scopedVmIds = groupVms.stream().map(Vm::getVmId).toList();
            estimate.setScopeLevel("GROUP");
            estimate.setScopeId(groupId);
            estimate.setScopeName(group.getDisplayName() != null ? group.getDisplayName() : group.getName());
            if (scopedVmIds.isEmpty()) {
                estimate.setSampleCount(0);
                estimate.setVmEstimates(new ArrayList<>());
                return estimate;
            }
        } else {
            // Environment scope
            Environment env = environmentRepository.findById(environmentId).orElse(null);
            estimate.setScopeLevel("ENVIRONMENT");
            estimate.setScopeId(environmentId);
            estimate.setScopeName(env != null ? env.getName() : environmentId);
        }

        // --- Fetch last 10 completed executions for this environment + operationType ---
        // operationType must be UPPERCASE to match what OperationType.name() stores in DB (e.g. "START", "STOP")
        List<OperationExecution> executions = executionRepository
                .findRecentCompletedByEnvironmentAndType(environmentId, operationType.toUpperCase(), PageRequest.of(0, 10));

        if (executions.isEmpty()) {
            estimate.setSampleCount(0);
            estimate.setVmEstimates(new ArrayList<>());
            return estimate;
        }

        List<String> execIds = executions.stream().map(OperationExecution::getExecutionId).toList();

        // --- Fetch relevant details (scoped or all) ---
        List<OperationDetail> details;
        if (scopedVmIds != null) {
            details = detailRepository.findCompletedDetailsByExecutionIdsAndVmIds(execIds, scopedVmIds);
        } else {
            details = detailRepository.findCompletedDetailsByExecutionIds(execIds);
        }

        if (details.isEmpty()) {
            estimate.setSampleCount(0);
            estimate.setVmEstimates(new ArrayList<>());
            return estimate;
        }

        // --- Compute wall-clock totals per execution (sum of scoped VM durations within each run) ---
        // Group details by executionId first
        Map<String, List<OperationDetail>> byExec = new LinkedHashMap<>();
        for (OperationDetail d : details) {
            byExec.computeIfAbsent(d.getExecution().getExecutionId(), k -> new ArrayList<>()).add(d);
        }

        // For each execution compute the total duration of scoped VMs
        // (from earliest startedAt to latest completedAt within that execution)
        List<Double> totalDurations = new ArrayList<>();
        for (List<OperationDetail> execDetails : byExec.values()) {
            long earliest = execDetails.stream()
                    .mapToLong(d -> d.getStartedAt().getTime())
                    .min().orElse(0);
            long latest = execDetails.stream()
                    .mapToLong(d -> d.getCompletedAt().getTime())
                    .max().orElse(0);
            if (latest > earliest) {
                totalDurations.add((latest - earliest) / 1000.0);
            }
        }

        if (!totalDurations.isEmpty()) {
            double avg = totalDurations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double min = totalDurations.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = totalDurations.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            estimate.setSampleCount(totalDurations.size());
            estimate.setAvgEnvironmentSeconds(round1(avg));
            estimate.setMinEnvironmentSeconds(round1(min));
            estimate.setMaxEnvironmentSeconds(round1(max));
        } else {
            estimate.setSampleCount(0);
        }

        // --- Per-VM breakdown (only for ENVIRONMENT scope — group/VM show totals only) ---
        if (scopedVmIds == null) {
            // Environment scope: build per-VM breakdown
            Map<String, List<OperationDetail>> byVm = new LinkedHashMap<>();
            Map<String, String> vmNames = new LinkedHashMap<>();
            Map<String, Integer> minSeq = new LinkedHashMap<>();

            for (OperationDetail d : details) {
                byVm.computeIfAbsent(d.getTargetId(), k -> new ArrayList<>()).add(d);
                vmNames.put(d.getTargetId(), d.getTargetName());
                minSeq.merge(d.getTargetId(), d.getSequencePosition(),
                        (existing, newVal) -> Math.min(existing, newVal));
            }

            List<OperationEstimateDTO.VmEstimate> vmEstimates = byVm.entrySet().stream()
                    .map(entry -> {
                        String vid = entry.getKey();
                        List<OperationDetail> vmDetails = entry.getValue();
                        double avgSec = vmDetails.stream()
                                .mapToDouble(d -> (d.getCompletedAt().getTime() - d.getStartedAt().getTime()) / 1000.0)
                                .average().orElse(0);
                        OperationEstimateDTO.VmEstimate ve = new OperationEstimateDTO.VmEstimate();
                        ve.setVmId(vid);
                        ve.setVmName(vmNames.get(vid));
                        ve.setAvgSeconds(round1(avgSec));
                        ve.setSampleCount(vmDetails.size());
                        ve.setSequencePosition(minSeq.getOrDefault(vid, 0));
                        return ve;
                    })
                    .sorted(Comparator.comparingInt(OperationEstimateDTO.VmEstimate::getSequencePosition))
                    .collect(java.util.stream.Collectors.toList());

            estimate.setVmEstimates(vmEstimates);
        } else {
            estimate.setVmEstimates(new ArrayList<>());
        }

        return estimate;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Cancel a pending or in-progress execution of the path environment. Only the user who
     * started it, the environment's lock holder, or someone who administers the environment
     * may cancel (H11).
     */
    @Transactional
    public OperationExecution cancelExecution(String environmentId, String executionId, String userId) {
        OperationExecution execution = getExecutionForEnvironment(environmentId, executionId);

        boolean initiator = userId != null && userId.equals(execution.getInitiatedByUserId());
        boolean lockHolder = userId != null && lockService.getCurrentLock(environmentId)
                .map(lock -> userId.equals(lock.getLockedByUserId()))
                .orElse(false);
        if (!initiator && !lockHolder && !securityService.canAdministerEnvironment(environmentId)) {
            throw new UnauthorizedException(
                    "Only the person who started this operation, the lock holder or an admin can cancel it");
        }

        if (execution.getStatus() != ExecutionStatus.PENDING &&
            execution.getStatus() != ExecutionStatus.IN_PROGRESS) {
            throw new ValidationException("Cannot cancel execution in status: " + execution.getStatus());
        }

        // Conditional: loses cleanly if the run finished (or another cancel landed) meanwhile.
        String environmentName = execution.getEnvironment().getName();
        String operationType = execution.getOperationType().name();
        if (executionRepository.transitionStatus(executionId, ACTIVE_STATUSES,
                OperationExecution.statusValue(ExecutionStatus.CANCELLED),
                Timestamp.from(Instant.now()), "Cancelled by user: " + userId) == 0) {
            throw new ValidationException("Operation already finished");
        }

        // Cancel pending details
        List<OperationDetail> pendingDetails = detailRepository
                .findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(executionId, "pending");

        for (OperationDetail detail : pendingDetails) {
            detail.setStatus("cancelled");
        }
        detailRepository.saveAll(pendingDetails);

        log.info("Cancelled execution {} by user {}", executionId, userId);
        auditService.logEnvironmentAction(userId, AuditAction.OPERATION_CANCELLED, environmentId,
                environmentName, "operation", executionId, operationType, "Cancelled");

        return getExecutionForEnvironment(environmentId, executionId);
    }

    /**
     * Execute operation asynchronously.
     */
    @Async("operationExecutionExecutor")
    public void executeOperationAsync(String executionId, boolean continueOnFailure) {
        try {
            executeOperation(executionId, continueOnFailure);
        } catch (Exception e) {
            log.error("Error executing operation {}: {}", executionId, e.getMessage(), e);
            markExecutionFailed(executionId, e.getMessage());
        }
    }

    /**
     * Core execution logic - processes VMs in dependency-ordered waves.
     * Each wave is every still-pending VM whose dependencies have all reached a terminal
     * state; the whole wave is submitted to {@code vmOperationExecutor} at once, so
     * independent VMs (the common case for a group start) start and poll their AWS status
     * checks in parallel instead of one blocking the next. A VM whose dependency failed or
     * was skipped is itself skipped rather than attempted.
     * continueOnFailure is retained on the signature for API compatibility but no longer
     * triggers an all-or-nothing abort — dependency state is what decides skip vs. attempt now.
     */
    private void executeOperation(String executionId, boolean continueOnFailure) {
        // PENDING -> IN_PROGRESS only if nobody cancelled it before the worker picked it up.
        if (executionRepository.transitionStatus(executionId,
                List.of(OperationExecution.statusValue(ExecutionStatus.PENDING)),
                OperationExecution.statusValue(ExecutionStatus.IN_PROGRESS), null, null) == 0) {
            log.info("Execution {} was cancelled before it started; nothing to do", executionId);
            return;
        }
        OperationExecution execution = getExecution(executionId);
        // Captured up front so the per-VM steps (which run on worker threads, off any session)
        // never have to navigate detail.getExecution() for a non-id property.
        final OperationType operationType = execution.getOperationType();
        final String initiatedByUserId = execution.getInitiatedByUserId();

        // Get all pending details, in dependency order
        List<OperationDetail> details = detailRepository
                .findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(executionId, "pending");

        if (details.isEmpty()) {
            finishExecution(executionId);
            return;
        }

        Map<String, String> targetNameByDetailId = details.stream()
                .collect(Collectors.toMap(OperationDetail::getDetailId, OperationDetail::getTargetName));
        Map<String, String> actionByDetailId = details.stream()
                .collect(Collectors.toMap(OperationDetail::getDetailId, d -> d.getAction() != null ? d.getAction() : ""));
        Map<String, List<String>> depsByDetailId = details.stream()
                .collect(Collectors.toMap(OperationDetail::getDetailId,
                        d -> parseDependsOnDetailIds(d.getDependsOnDetailIds())));
        Set<String> runDetailIds = new HashSet<>(targetNameByDetailId.keySet());

        Map<String, String> finalStatusByDetailId = new HashMap<>();
        List<OperationDetail> remaining = new ArrayList<>(details);

        while (!remaining.isEmpty()) {
            if (getExecution(executionId).getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was cancelled before the next wave", executionId);
                return;
            }

            // A detail is ready once every in-scope dependency has a recorded terminal status.
            // Dependency ids outside this run's detail set are treated as already satisfied.
            List<OperationDetail> ready = remaining.stream()
                    .filter(d -> depsByDetailId.getOrDefault(d.getDetailId(), List.of()).stream()
                            .filter(runDetailIds::contains)
                            .allMatch(finalStatusByDetailId::containsKey))
                    .toList();

            if (ready.isEmpty()) {
                // Shouldn't happen — cycles are rejected at creation time. Run the remainder
                // as one final wave rather than spin forever.
                log.warn("Execution {}: {} detail(s) with unresolved dependencies; running them as a final wave",
                        executionId, remaining.size());
                ready = new ArrayList<>(remaining);
            }

            // Split the wave into VMs blocked by a failed/skipped dependency vs. runnable ones.
            List<OperationDetail> runnable = new ArrayList<>();
            for (OperationDetail detail : ready) {
                String blockingDetailId = findBlockingDependency(
                        depsByDetailId.getOrDefault(detail.getDetailId(), List.of()), finalStatusByDetailId);
                if (blockingDetailId != null) {
                    skipDetail(detail, skipReason(detail.getAction(),
                            actionByDetailId.get(blockingDetailId),
                            targetNameByDetailId.getOrDefault(blockingDetailId, blockingDetailId)), executionId);
                    finalStatusByDetailId.put(detail.getDetailId(), "skipped");
                } else {
                    runnable.add(detail);
                }
            }

            if (!runnable.isEmpty()) {
                CompletableFuture<?>[] futures = runnable.stream()
                        .map(detail -> CompletableFuture.runAsync(() -> {
                            try {
                                executeVmOperation(detail, stepType(detail, operationType), executionId,
                                        initiatedByUserId);
                            } catch (Exception e) {
                                log.error("Error executing operation on {}: {}",
                                        detail.getTargetName(), e.getMessage());
                            }
                        }, vmOperationExecutor))
                        .toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(futures).join();

                for (OperationDetail detail : runnable) {
                    OperationDetail reloaded = detailRepository.findById(detail.getDetailId()).orElse(detail);
                    boolean terminal = reloaded.isCompleted() || reloaded.isFailed() || reloaded.isSkipped()
                            || reloaded.isCancelled();
                    // Task threw before persisting a terminal status — count it as failed.
                    String status = terminal ? reloaded.getStatus() : "failed";
                    finalStatusByDetailId.put(detail.getDetailId(), status);
                    if (!terminal) {
                        executionRepository.incrementCounters(executionId, 0, 1, 0);
                    }
                }
            }

            remaining.removeAll(ready);

            boolean waveFailed = runnable.stream()
                    .anyMatch(d -> "failed".equals(finalStatusByDetailId.get(d.getDetailId())));
            if (waveFailed && !continueOnFailure && !remaining.isEmpty()) {
                log.info("Execution {}: a step failed and continue-on-failure is off; skipping {} remaining step(s)",
                        executionId, remaining.size());
                for (OperationDetail detail : remaining) {
                    skipDetail(detail, "Skipped: an earlier step failed and continue-on-failure is off", executionId);
                }
                remaining.clear();
            }

            if (getExecution(executionId).getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was cancelled after a wave completed, stopping", executionId);
                return;
            }
        }

        finishExecution(executionId);
    }

    /**
     * Returns the target name of the first dependency detail whose final status is failed or
     * skipped, or null if all dependencies (so far known) are clear. Dependency ids with no
     * recorded status yet are treated as satisfied — execution order guarantees dependencies
     * are processed first, so this only happens for ids outside the current run's detail set.
     */
    private String findBlockingDependency(List<String> depDetailIds, Map<String, String> finalStatusByDetailId) {
        for (String depId : depDetailIds) {
            String status = finalStatusByDetailId.get(depId);
            if ("failed".equals(status) || "skipped".equals(status)) {
                return depId;
            }
        }
        return null;
    }

    /** Why a step is skipped, in terms of the step it was waiting for. */
    static String skipReason(String blockedAction, String blockingAction, String blockingName) {
        if ("stop".equals(blockingAction) && "stop".equals(blockedAction)) {
            // STOP runs in reverse order: the blocker is a VM that depends on this one.
            return "Skipped: '" + blockingName + "' depends on this VM and did not stop";
        }
        if ("stop".equals(blockingAction)) {
            // RESTART: the start of a VM waits for its own (or a dependent's) stop.
            return "Skipped: '" + blockingName + "' did not stop";
        }
        return "Skipped: dependency '" + blockingName + "' did not start";
    }

    private void skipDetail(OperationDetail detail, String reason, String executionId) {
        detail.setStatus("skipped");
        detail.setErrorMessage(reason);
        detail.setCompletedAt(Timestamp.from(Instant.now()));
        detailRepository.save(detail);
        executionRepository.incrementCounters(executionId, 0, 0, 1);
        log.warn("{} ({})", reason, detail.getTargetName());
    }

    /** The provider call a step makes: its own action, so a RESTART runs as stop steps then start steps. */
    private static OperationType stepType(OperationDetail detail, OperationType executionType) {
        if ("stop".equals(detail.getAction())) {
            return OperationType.STOP;
        }
        if ("start".equals(detail.getAction())) {
            return OperationType.START;
        }
        return executionType;
    }

    /** One planned step: a VM, its action, and the keys of the steps it waits for. */
    record PlannedStep(Vm vm, String action, List<String> dependsOnKeys) {
        String key() {
            return action + ":" + vm.getVmId();
        }

        static String key(String action, String vmId) {
            return action + ":" + vmId;
        }
    }

    /**
     * Steps in execution order (H2). START: prerequisites first. STOP: reverse order, a VM after
     * everything in scope that depends on it. RESTART: every stop (in STOP order), then every
     * start (in START order), where a VM's start also waits for its own stop.
     */
    List<PlannedStep> planSteps(List<Vm> vms, OperationType operationType) {
        List<PlannedStep> plan = new ArrayList<>();
        if (operationType == OperationType.START || operationType == OperationType.RESTART) {
            if (operationType == OperationType.RESTART) {
                plan.addAll(phase(vms, OperationType.STOP, "stop", null));
            }
            plan.addAll(phase(vms, OperationType.START, "start",
                    operationType == OperationType.RESTART ? "stop" : null));
        } else {
            plan.addAll(phase(vms, OperationType.STOP, "stop", null));
        }
        return plan;
    }

    private List<PlannedStep> phase(List<Vm> vms, OperationType orderType, String action, String afterOwnAction) {
        List<Vm> ordered = dependencyValidator.orderForExecution(vms, orderType);
        Map<String, List<String>> waitsFor = dependencyValidator.buildScopedDependencyMap(ordered, orderType);
        List<PlannedStep> steps = new ArrayList<>();
        for (Vm vm : ordered) {
            List<String> keys = new ArrayList<>();
            if (afterOwnAction != null) {
                keys.add(PlannedStep.key(afterOwnAction, vm.getVmId()));
            }
            waitsFor.getOrDefault(vm.getVmId(), List.of())
                    .forEach(other -> keys.add(PlannedStep.key(action, other)));
            steps.add(new PlannedStep(vm, action, keys));
        }
        return steps;
    }

    private List<String> parseDependsOnDetailIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Could not parse dependsOnDetailIds '{}': {}", json, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialize value to JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute operation on a single VM.
     */
    private void executeVmOperation(OperationDetail detail, OperationType operationType,
                                   String executionId, String initiatedByUserId) {
        try {
            // Mark as in progress
            detail.setStatus("in_progress");
            detail.setStartedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);

            // Get VM
            Vm vm = vmRepository.findById(detail.getTargetId()).orElse(null);
            if (vm == null) {
                detail.setStatus("failed");
                detail.setErrorMessage("VM not found: " + detail.getTargetId());
                detail.setCompletedAt(Timestamp.from(Instant.now()));
                detailRepository.save(detail);
                updateExecutionCounters(executionId, false);
                return;
            }

            CloudProviderService providerService = cloudProviderFactory.getService(vm.getProvider());

            CloudProviderService.VmOperationResult result;
            VmStatus previousStatus = vm.getStatus();
            final String detailId = detail.getDetailId();

            if (operationType == OperationType.START) {
                updateOperationProgress(executionId, detailId,
                        CloudProviderService.VmOperationProgress.of(VmStatus.STARTING, "Start requested", 10));
                result = providerService.startVm(vm.getProviderVmId(), vm.getRegion(),
                        progress -> updateOperationProgress(executionId, detailId, progress)).join();
            } else {
                // Every step is a start or a stop (a RESTART is planned as stop steps then start steps).
                updateOperationProgress(executionId, detailId,
                        CloudProviderService.VmOperationProgress.of(VmStatus.STOPPING, "Stop requested", 10));
                result = providerService.stopVm(vm.getProviderVmId(), vm.getRegion(), false,
                        progress -> updateOperationProgress(executionId, detailId, progress)).join();
            }

            detail = detailRepository.findById(detailId).orElse(detail);

            // A timed-out step already reports the last real status: no second live check (M9).
            VmStatus reconciledStatus = result.isSuccess()
                    ? result.getResultStatus()
                    : result.isTimedOut() ? null : reconcileCloudStateAfterFailure(providerService, vm, operationType);

            if (result.isSuccess() || reconciledStatus != null) {
                detail.setStatus("completed");
                detail.setCloudOperationId(result.getRequestId());
                detail.setProgressPercentage(100);
                detail.setStageLabel(operationType == OperationType.STOP ? "Stop completed" : "Start completed");

                // Update VM status
                if (reconciledStatus != null) {
                    if (reconciledStatus != previousStatus) {
                        stateSyncService.recordStateChange(vm, previousStatus, reconciledStatus, "operation",
                                initiatedByUserId, executionId,
                                operationType + " operation completed");
                    }
                    writeStatus(vm.getVmId(), reconciledStatus);
                }

                updateExecutionCounters(executionId, true);
            } else {
                detail.setStatus("failed");
                detail.setErrorMessage(result.getMessage());
                if (result.isTimedOut() && result.getResultStatus() != null) {
                    // Still STOPPING (say) when polling gave up: record that, not the old status.
                    writeStatus(vm.getVmId(), result.getResultStatus());
                } else if (shouldRestorePreviousStatus(operationType, result)) {
                    writeStatus(vm.getVmId(), previousStatus);
                }
                updateExecutionCounters(executionId, false);
            }

            detail.setCompletedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);

            log.info("VM operation {} on {} completed: {}",
                    operationType, vm.getName(), detail.getStatus());

        } catch (OperationCancelledException e) {
            markDetailCancelled(detail, e.getMessage());
        } catch (CompletionException ce) {
            if (ce.getCause() instanceof OperationCancelledException) {
                markDetailCancelled(detail, ce.getCause().getMessage());
                return;
            }
            log.error("Error executing operation on {}: {}", detail.getTargetName(), ce.getMessage());
            detail.setStatus("failed");
            detail.setErrorMessage(ce.getMessage());
            detail.setCompletedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);
            updateExecutionCounters(executionId, false);
        } catch (Exception e) {
            log.error("Error executing operation on {}: {}", detail.getTargetName(), e.getMessage());

            detail.setStatus("failed");
            detail.setErrorMessage(e.getMessage());
            detail.setCompletedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);

            updateExecutionCounters(executionId, false);
        }
    }

    /**
     * Marks a detail whose in-flight cloud call was aborted because its execution was
     * cancelled — deliberately not counted via {@link #updateExecutionCounters}, since this
     * isn't a failure and the wave loop stops once it notices the execution is CANCELLED.
     */
    private void markDetailCancelled(OperationDetail detail, String message) {
        log.info("Operation on {} stopped: {}", detail.getTargetName(), message);
        detail.setStatus("cancelled");
        detail.setErrorMessage(message);
        detail.setCompletedAt(Timestamp.from(Instant.now()));
        detailRepository.save(detail);
    }

    /**
     * Set a VM's status with a targeted conditional update (H14): never save the VM copy loaded
     * at the start of the step, which would overwrite what the provider wrote meanwhile (EKS
     * node-group sizes in metadata). Retries when another writer changes the status in between.
     */
    private boolean writeStatus(String vmId, VmStatus newStatus) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<VmStatus> current = vmRepository.findStatusById(vmId);
            if (current.isEmpty()) {
                return false;
            }
            if (vmRepository.updateStatusIfCurrent(vmId, current.get(), newStatus, Timestamp.from(Instant.now())) == 1) {
                return true;
            }
        }
        log.warn("Could not set VM {} to {}: its status kept changing", vmId, newStatus);
        return false;
    }

    /**
     * Persists provider progress for a detail and, on each tick, checks whether its execution
     * has been cancelled — if so, throws to unwind the cloud provider's poll loop early instead
     * of waiting out its full timeout (see {@link OperationCancelledException}).
     */
    private void updateOperationProgress(String executionId, String detailId,
                                         CloudProviderService.VmOperationProgress progress) {
        if (progress == null) {
            return;
        }

        if (getExecution(executionId).getStatus() == ExecutionStatus.CANCELLED) {
            throw new OperationCancelledException("Execution " + executionId + " was cancelled");
        }

        detailRepository.findById(detailId).ifPresent(detail -> {
            detail.setStageLabel(progress.getStageLabel());
            detail.setProgressPercentage(progress.getProgressPercentage());
            detail.setStatusChecksPassed(progress.getStatusChecksPassed());
            detail.setStatusChecksTotal(progress.getStatusChecksTotal());
            detailRepository.save(detail);

            if (progress.getStatus() != null) {
                writeStatus(detail.getTargetId(), progress.getStatus());
            }
        });
    }

    private VmStatus reconcileCloudStateAfterFailure(CloudProviderService providerService, Vm vm,
                                                     OperationType operationType) {
        try {
            VmStatus currentStatus = providerService.getVmStatus(vm.getProviderVmId(), vm.getRegion());
            if (isAcceptablePostFailureState(operationType, currentStatus)) {
                log.warn("Provider reported failure for VM {}, but cloud state is {}; treating operation as accepted",
                        vm.getName(), currentStatus);
                return currentStatus;
            }
        } catch (Exception e) {
            log.warn("Could not reconcile cloud state after provider failure for VM {}: {}",
                    vm.getName(), e.getMessage());
        }
        return null;
    }

    private boolean isAcceptablePostFailureState(OperationType operationType, VmStatus status) {
        switch (operationType) {
            case STOP:
                // Only a real STOPPED counts; STOPPING is not done (M9).
                return status == VmStatus.STOPPED;
            case START:
                // The provider poll gave up (typically because AWS status checks were slow
                // to all report OK, or a transient DescribeInstances error), but the cloud
                // shows the instance actually RUNNING — the start succeeded and the VM is
                // usable. Mirrors the STOP branch above so a healthy start is not recorded
                // as a failed step (which would turn the whole execution PARTIAL_SUCCESS and
                // surface "Completed with some failures" for a VM that is up).
                return status == VmStatus.RUNNING;
            default:
                // RESTART is multi-phase (stop then start); "is it actually fine?" is
                // ambiguous on a mid-sequence failure, so leave it strict.
                return false;
        }
    }

    private boolean shouldRestorePreviousStatus(OperationType operationType,
                                                CloudProviderService.VmOperationResult result) {
        if (operationType != OperationType.START) {
            return true;
        }
        String message = result != null ? result.getMessage() : null;
        return message == null || !message.startsWith("AWS status checks");
    }

    // ============= Private Helper Methods =============

    /**
     * Applies group-scoped access to the target VM set.
     * <ul>
     *   <li>Users with environment-wide USER access (or a global role) pass unchanged.</li>
     *   <li>For a request with explicit {@code vmIds}/{@code groupIds}: if any targeted group
     *       is one the user cannot operate, the whole request is rejected (403), naming the
     *       groups — no silent partial run.</li>
     *   <li>For a whole-environment request: the set is narrowed to the groups the user can
     *       operate; empty result is rejected.</li>
     * </ul>
     */
    private List<Vm> enforceGroupScopeAccess(List<Vm> targetVms, StartOperationDTO dto, String userId) {
        Map<String, Boolean> operableByGroup = new HashMap<>();
        List<String> disallowedGroupIds = new ArrayList<>();
        for (Vm vm : targetVms) {
            String groupId = vm.getGroup().getGroupId();
            boolean operable = operableByGroup.computeIfAbsent(groupId,
                    g -> securityService.hasGroupAccessLevelForUser(userId, g, AccessLevel.USER));
            if (!operable && !disallowedGroupIds.contains(groupId)) {
                disallowedGroupIds.add(groupId);
            }
        }
        if (disallowedGroupIds.isEmpty()) {
            return targetVms;
        }

        boolean wholeEnvironment = (dto.getVmIds() == null || dto.getVmIds().isEmpty())
                && (dto.getGroupIds() == null || dto.getGroupIds().isEmpty());
        if (wholeEnvironment) {
            List<Vm> narrowed = targetVms.stream()
                    .filter(vm -> operableByGroup.get(vm.getGroup().getGroupId()))
                    .toList();
            if (narrowed.isEmpty()) {
                throw new UnauthorizedException(
                        "You do not have access to operate any VM group in this environment");
            }
            log.info("Operation by {} narrowed to {} of {} target VMs (group-scoped access)",
                    userId, narrowed.size(), targetVms.size());
            return narrowed;
        }

        Map<String, String> names = groupRepository.findAllById(disallowedGroupIds).stream()
                .collect(Collectors.toMap(VmGroup::getGroupId, VmGroup::getDisplayName));
        String label = disallowedGroupIds.stream()
                .map(g -> names.getOrDefault(g, g))
                .collect(Collectors.joining(", "));
        throw new UnauthorizedException("You cannot operate on VM group(s): " + label);
    }

    /** A VM of the given environment, or 404 (also for a VM of another environment). */
    private Vm vmInEnvironment(String environmentId, String vmId) {
        return vmRepository.findById(vmId)
                .filter(vm -> vm.getGroup() != null
                        && environmentId.equals(vm.getGroup().getEnvironment().getEnvironmentId()))
                .orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
    }

    /** A group of the given environment, or 404 (also for a group of another environment). */
    private VmGroup groupInEnvironment(String environmentId, String groupId) {
        return groupRepository.findById(groupId)
                .filter(group -> environmentId.equals(group.getEnvironment().getEnvironmentId()))
                .orElseThrow(() -> new ResourceNotFoundException("VmGroup", groupId));
    }

    private List<Vm> resolveTargetVms(Environment environment, StartOperationDTO dto) {
        List<Vm> targetVms;

        if (dto.getVmIds() != null && !dto.getVmIds().isEmpty()) {
            // Specific VMs requested - filter out inactive VMs
            // Every id must be a VM of the path environment: an unknown or foreign id is a 404
            // (not silently used), so a lock on A can never touch B's VMs (H1).
            List<Vm> resolvedVms = new ArrayList<>();
            for (String vmId : dto.getVmIds()) {
                Vm vm = vmInEnvironment(environment.getEnvironmentId(), vmId);
                if (Boolean.TRUE.equals(vm.getIsActive())) {
                    resolvedVms.add(vm);
                } else {
                    log.warn("Skipping inactive VM {} ({}) - status: {}",
                            vm.getName(), vmId, vm.getStatus());
                }
            }
            targetVms = resolvedVms;
        } else if (dto.getGroupIds() != null && !dto.getGroupIds().isEmpty()) {
            // Specific groups requested - repository already filters inactive VMs
            targetVms = new ArrayList<>();
            for (String groupId : dto.getGroupIds()) {
                groupInEnvironment(environment.getEnvironmentId(), groupId);
                targetVms.addAll(vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId));
            }
        } else {
            // All VMs in environment - repository already filters inactive VMs
            targetVms = vmRepository.findByEnvironmentId(environment.getEnvironmentId());
        }

        // Filter out VMs already in target state if requested
        if (dto.isSkipAlreadyInTargetState()) {
            VmStatus targetStatus = dto.getOperationType() == OperationType.START
                    ? VmStatus.RUNNING : VmStatus.STOPPED;

            targetVms = targetVms.stream()
                    .filter(vm -> vm.getStatus() != targetStatus)
                    .toList();
        }

        return new ArrayList<>(targetVms);
    }

    private void updateExecutionCounters(String executionId, boolean success) {
        executionRepository.incrementCounters(executionId, success ? 1 : 0, success ? 0 : 1, 0);
    }

    /**
     * Final status from the counters (M8): COMPLETED when every step succeeded, FAILED when none
     * did (all failed or skipped), otherwise PARTIAL_SUCCESS. Only moves an IN_PROGRESS run, so a
     * cancel that landed meanwhile stays CANCELLED and sends no completion notice.
     */
    private void finishExecution(String executionId) {
        OperationExecution execution = executionRepository.findByIdWithEnvironment(executionId).orElse(null);
        if (execution == null) {
            return;
        }
        int total = execution.getTotalTargets();
        int completed = execution.getCompletedTargets();
        int failed = execution.getFailedTargets();
        int skipped = execution.getSkippedTargets() != null ? execution.getSkippedTargets() : 0;

        ExecutionStatus outcome;
        String message = null;
        if (failed == 0 && skipped == 0) {
            outcome = ExecutionStatus.COMPLETED;
        } else if (completed == 0) {
            outcome = ExecutionStatus.FAILED;
            message = skipped == 0 ? "All " + total + " steps failed"
                    : "No step succeeded (" + failed + " failed, " + skipped + " skipped)";
        } else {
            outcome = ExecutionStatus.PARTIAL_SUCCESS;
        }

        if (executionRepository.transitionStatus(executionId,
                List.of(OperationExecution.statusValue(ExecutionStatus.IN_PROGRESS)),
                OperationExecution.statusValue(outcome), Timestamp.from(Instant.now()), message) == 0) {
            log.info("Execution {} is no longer in progress (cancelled?) — not marking it {}", executionId, outcome);
            return;
        }
        log.info("Execution {} finished: {} ({} ok, {} failed, {} skipped)",
                executionId, outcome, completed, failed, skipped);

        if (outcome == ExecutionStatus.FAILED) {
            reportFailure(execution, message);
            return;
        }
        runTerminalSideEffect("audit finished operation", executionId, () ->
                auditService.logOperationCompleted(
                        execution.getInitiatedByUserId(),
                        execution.getEnvironment().getEnvironmentId(),
                        execution.getEnvironment().getName(),
                        executionId,
                        execution.getOperationType().name(),
                        total,
                        failed + skipped));
        runTerminalSideEffect("notify finished operation", executionId, () ->
                notificationService.notifyOperationCompletedForEnvironment(
                        execution.getEnvironment().getEnvironmentId(),
                        execution.getEnvironment().getName(),
                        execution.getInitiatedByUserId(),
                        execution.getOperationType().name(),
                        total,
                        failed + skipped));
    }

    /** The run itself broke (not a step): FAILED unless it already reached another final state. */
    private void markExecutionFailed(String executionId, String errorMessage) {
        if (executionRepository.transitionStatus(executionId, ACTIVE_STATUSES,
                OperationExecution.statusValue(ExecutionStatus.FAILED),
                Timestamp.from(Instant.now()), errorMessage) == 0) {
            log.warn("Execution {} errored after it was finished or cancelled: {}", executionId, errorMessage);
            return;
        }
        log.error("Execution {} failed: {}", executionId, errorMessage);
        executionRepository.findByIdWithEnvironment(executionId)
                .ifPresent(execution -> reportFailure(execution, errorMessage));
    }

    private void reportFailure(OperationExecution execution, String errorMessage) {
        String executionId = execution.getExecutionId();
        runTerminalSideEffect("audit failed operation", executionId, () ->
                auditService.logOperationFailed(
                        execution.getInitiatedByUserId(),
                        execution.getEnvironment().getEnvironmentId(),
                        execution.getEnvironment().getName(),
                        executionId,
                        execution.getOperationType().name(),
                        errorMessage
                ));
        runTerminalSideEffect("notify failed operation", executionId, () ->
                notificationService.notifyOperationFailedForEnvironment(
                        execution.getEnvironment().getEnvironmentId(),
                        execution.getEnvironment().getName(),
                        execution.getInitiatedByUserId(),
                        execution.getOperationType().name(),
                        errorMessage));
    }

    private void runTerminalSideEffect(String action, String executionId, Runnable runnable) {
        runSideEffect(action, executionId, runnable);
    }

    private void runSideEffect(String action, String executionId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for execution {}: {}", action, executionId, e.getMessage());
        }
    }

    private String operationScopeLabel(StartOperationDTO dto) {
        if (dto.getVmIds() != null && !dto.getVmIds().isEmpty()) {
            return dto.getVmIds().size() == 1 ? "1 VM" : dto.getVmIds().size() + " VMs";
        }
        if (dto.getGroupIds() != null && !dto.getGroupIds().isEmpty()) {
            return dto.getGroupIds().size() == 1 ? "1 group" : dto.getGroupIds().size() + " groups";
        }
        return "the environment";
    }
}
