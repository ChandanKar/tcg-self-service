package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.OperationEstimateDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.stream.Collectors;

/**
 * Service for orchestrating VM operations across an environment.
 * Handles dependency ordering, batch execution, and status tracking.
 */
@Service
public class VmOperationsService {

    private static final Logger log = LoggerFactory.getLogger(VmOperationsService.class);

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

    // Self-reference via proxy so @Async is properly applied (self-invocation bypasses Spring proxy)
    @Lazy
    @Autowired
    private VmOperationsService self;

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
                               NotificationService notificationService) {
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

        // Get target VMs based on request
        List<Vm> targetVms = resolveTargetVms(environment, dto);

        if (targetVms.isEmpty()) {
            throw new ValidationException("No VMs to operate on");
        }

        // Create execution record
        OperationExecution execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(environment);
        execution.setOperationType(dto.getOperationType());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setInitiatedByUserId(userId);
        execution.setTotalTargets(targetVms.size());

        execution = executionRepository.save(execution);

        // Create detail records for each VM
        String actionName = dto.getOperationType() == OperationType.START ? "start" : "stop";
        int sequencePosition = 0;

        for (Vm vm : targetVms) {
            OperationDetail detail = new OperationDetail();
            detail.setDetailId(UUID.randomUUID().toString());
            detail.setExecution(execution);
            detail.setTargetType("vm");
            detail.setTargetId(vm.getVmId());
            detail.setTargetName(vm.getName());
            detail.setAction(actionName);
            detail.setStatus("pending");
            detail.setSequencePosition(++sequencePosition);

            detailRepository.save(detail);
        }

        log.info("Created operation execution {} for environment {} ({} VMs)",
                execution.getExecutionId(), environmentId, targetVms.size());

        // Audit logging
        auditService.logOperationStarted(userId, environmentId, environment.getName(),
                execution.getExecutionId(), dto.getOperationType().name());

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
     * Get execution with details.
     */
    @Transactional(readOnly = true)
    public OperationExecution getExecutionWithDetails(String executionId) {
        OperationExecution execution = getExecution(executionId);
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
            // VM scope
            scopedVmIds = List.of(vmId);
            Vm vm = vmRepository.findById(vmId).orElse(null);
            estimate.setScopeLevel("VM");
            estimate.setScopeId(vmId);
            estimate.setScopeName(vm != null ? vm.getName() : vmId);
        } else if (groupId != null && !groupId.isBlank()) {
            // Group scope — resolve VMs in this group
            List<Vm> groupVms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId);
            scopedVmIds = groupVms.stream().map(Vm::getVmId).toList();
            VmGroup group = groupRepository.findById(groupId).orElse(null);
            estimate.setScopeLevel("GROUP");
            estimate.setScopeId(groupId);
            estimate.setScopeName(group != null ? (group.getDisplayName() != null ? group.getDisplayName() : group.getName()) : groupId);
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
     * Cancel a pending or in-progress execution.
     */
    @Transactional
    public OperationExecution cancelExecution(String executionId, String userId) {
        OperationExecution execution = getExecution(executionId);

        if (execution.getStatus() != ExecutionStatus.PENDING &&
            execution.getStatus() != ExecutionStatus.IN_PROGRESS) {
            throw new ValidationException("Cannot cancel execution in status: " + execution.getStatus());
        }

        execution.setStatus(ExecutionStatus.CANCELLED);
        execution.setCompletedAt(Timestamp.from(Instant.now()));
        execution.setErrorMessage("Cancelled by user: " + userId);

        // Cancel pending details
        List<OperationDetail> pendingDetails = detailRepository
                .findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(executionId, "pending");

        for (OperationDetail detail : pendingDetails) {
            detail.setStatus("cancelled");
        }
        detailRepository.saveAll(pendingDetails);

        log.info("Cancelled execution {} by user {}", executionId, userId);

        return executionRepository.save(execution);
    }

    /**
     * Execute operation asynchronously.
     */
    @Async
    public void executeOperationAsync(String executionId, boolean continueOnFailure) {
        try {
            executeOperation(executionId, continueOnFailure);
        } catch (Exception e) {
            log.error("Error executing operation {}: {}", executionId, e.getMessage(), e);
            markExecutionFailed(executionId, e.getMessage());
        }
    }

    /**
     * Core execution logic - processes VMs sequentially by sequence position.
     */
    private void executeOperation(String executionId, boolean continueOnFailure) {
        OperationExecution execution = getExecution(executionId);

        // Mark as in progress
        execution.setStatus(ExecutionStatus.IN_PROGRESS);
        executionRepository.save(execution);

        // Get all pending details
        List<OperationDetail> details = detailRepository
                .findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(executionId, "pending");

        if (details.isEmpty()) {
            markExecutionCompleted(executionId);
            return;
        }

        boolean hasFailures = false;

        // Process each detail
        for (OperationDetail detail : details) {
            // Check before starting each VM
            execution = getExecution(executionId);
            if (execution.getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was cancelled before VM {}", executionId, detail.getTargetName());
                return;
            }

            try {
                executeVmOperation(detail, execution.getOperationType());

                // Re-check after the cloud call returns — a cancel request may have arrived
                // while the provider API was blocking (cloud calls are non-interruptible)
                execution = getExecution(executionId);
                if (execution.getStatus() == ExecutionStatus.CANCELLED) {
                    log.info("Execution {} was cancelled after completing VM {}, stopping",
                            executionId, detail.getTargetName());
                    return;
                }

                detail = detailRepository.findById(detail.getDetailId()).orElse(detail);
                if (detail.isFailed()) {
                    hasFailures = true;
                    if (!continueOnFailure) {
                        log.warn("VM operation failed, stopping execution {}", executionId);
                        markExecutionFailed(executionId, "Failed at VM: " + detail.getTargetName());
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("Error executing operation on {}: {}", detail.getTargetName(), e.getMessage());
                hasFailures = true;
                if (!continueOnFailure) {
                    markExecutionFailed(executionId, e.getMessage());
                    return;
                }
            }
        }

        // Mark execution complete
        if (hasFailures) {
            markExecutionPartialSuccess(executionId);
        } else {
            markExecutionCompleted(executionId);
        }
    }

    /**
     * Execute operation on a single VM.
     */
    private void executeVmOperation(OperationDetail detail, OperationType operationType) {
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
                updateExecutionCounters(detail.getExecution().getExecutionId(), false);
                return;
            }

            CloudProviderService providerService = cloudProviderFactory.getService(vm.getProvider());

            CloudProviderService.VmOperationResult result;
            VmStatus previousStatus = vm.getStatus();
            final String detailId = detail.getDetailId();

            if (operationType == OperationType.START) {
                updateOperationProgress(detailId,
                        CloudProviderService.VmOperationProgress.of(VmStatus.STARTING, "Start requested", 10));
                result = providerService.startVm(vm.getProviderVmId(), vm.getRegion(),
                        progress -> updateOperationProgress(detailId, progress)).join();
            } else if (operationType == OperationType.STOP) {
                updateOperationProgress(detailId,
                        CloudProviderService.VmOperationProgress.of(VmStatus.STOPPING, "Stop requested", 10));
                result = providerService.stopVm(vm.getProviderVmId(), vm.getRegion(), false,
                        progress -> updateOperationProgress(detailId, progress)).join();
            } else {
                // RESTART = stop then start
                updateOperationProgress(detailId,
                        CloudProviderService.VmOperationProgress.of(VmStatus.STOPPING, "EC2 stopping", 50));
                result = providerService.stopVm(vm.getProviderVmId(), vm.getRegion(), false,
                        progress -> updateOperationProgress(detailId, progress)).join();
                if (result.isSuccess()) {
                    Thread.sleep(5000);
                    updateOperationProgress(detailId,
                            CloudProviderService.VmOperationProgress.of(VmStatus.STARTING, "Start requested", 10));
                    result = providerService.startVm(vm.getProviderVmId(), vm.getRegion(),
                            progress -> updateOperationProgress(detailId, progress)).join();
                }
            }

            detail = detailRepository.findById(detailId).orElse(detail);

            VmStatus reconciledStatus = result.isSuccess()
                    ? result.getResultStatus()
                    : reconcileCloudStateAfterFailure(providerService, vm, operationType);

            if (result.isSuccess() || reconciledStatus != null) {
                detail.setStatus("completed");
                detail.setCloudOperationId(result.getRequestId());
                detail.setProgressPercentage(100);
                detail.setStageLabel(operationType == OperationType.STOP ? "Stop completed" : "Start completed");

                // Update VM status
                if (reconciledStatus != null) {
                    vm.setStatus(reconciledStatus);
                    vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
                    vmRepository.save(vm);
                }

                updateExecutionCounters(detail.getExecution().getExecutionId(), true);
            } else {
                detail.setStatus("failed");
                detail.setErrorMessage(result.getMessage());
                if (shouldRestorePreviousStatus(operationType, result)) {
                    vm.setStatus(previousStatus);
                    vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
                    vmRepository.save(vm);
                }
                updateExecutionCounters(detail.getExecution().getExecutionId(), false);
            }

            detail.setCompletedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);

            log.info("VM operation {} on {} completed: {}",
                    operationType, vm.getName(), detail.getStatus());

        } catch (Exception e) {
            log.error("Error executing operation on {}: {}", detail.getTargetName(), e.getMessage());

            detail.setStatus("failed");
            detail.setErrorMessage(e.getMessage());
            detail.setCompletedAt(Timestamp.from(Instant.now()));
            detailRepository.save(detail);

            updateExecutionCounters(detail.getExecution().getExecutionId(), false);
        }
    }

    private void markVmTransitioning(Vm vm, VmStatus status) {
        vm.setStatus(status);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);
    }

    private void updateOperationProgress(String detailId, CloudProviderService.VmOperationProgress progress) {
        if (progress == null) {
            return;
        }

        detailRepository.findById(detailId).ifPresent(detail -> {
            detail.setStageLabel(progress.getStageLabel());
            detail.setProgressPercentage(progress.getProgressPercentage());
            detail.setStatusChecksPassed(progress.getStatusChecksPassed());
            detail.setStatusChecksTotal(progress.getStatusChecksTotal());
            detailRepository.save(detail);

            Vm vm = vmRepository.findById(detail.getTargetId()).orElse(null);
            if (vm != null && progress.getStatus() != null) {
                markVmTransitioning(vm, progress.getStatus());
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
        if (operationType == OperationType.STOP) {
            return status == VmStatus.STOPPING || status == VmStatus.STOPPED;
        }
        return false;
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

    private List<Vm> resolveTargetVms(Environment environment, StartOperationDTO dto) {
        List<Vm> targetVms;

        if (dto.getVmIds() != null && !dto.getVmIds().isEmpty()) {
            // Specific VMs requested - filter out inactive VMs
            List<Vm> resolvedVms = new ArrayList<>();
            for (String vmId : dto.getVmIds()) {
                vmRepository.findById(vmId).ifPresent(vm -> {
                    if (Boolean.TRUE.equals(vm.getIsActive())) {
                        resolvedVms.add(vm);
                    } else {
                        log.warn("Skipping inactive VM {} ({}) - status: {}",
                                vm.getName(), vmId, vm.getStatus());
                    }
                });
            }
            targetVms = resolvedVms;
        } else if (dto.getGroupIds() != null && !dto.getGroupIds().isEmpty()) {
            // Specific groups requested - repository already filters inactive VMs
            targetVms = new ArrayList<>();
            for (String groupId : dto.getGroupIds()) {
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

    private synchronized void updateExecutionCounters(String executionId, boolean success) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            if (success) {
                execution.incrementCompleted();
            } else {
                execution.incrementFailed();
            }
            executionRepository.save(execution);
        });
    }

    private void markExecutionCompleted(String executionId) {
        executionRepository.findByIdWithEnvironment(executionId).ifPresent(execution -> {
            if (execution.getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was already cancelled — skipping COMPLETED transition", executionId);
                return;
            }
            execution.setStatus(ExecutionStatus.COMPLETED);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            executionRepository.save(execution);
            log.info("Execution {} completed successfully", executionId);

            runTerminalSideEffect("audit completed operation", executionId, () ->
                    auditService.logOperationCompleted(
                            execution.getInitiatedByUserId(),
                            execution.getEnvironment().getEnvironmentId(),
                            execution.getEnvironment().getName(),
                            executionId,
                            execution.getOperationType().name(),
                            execution.getTotalTargets(),
                            execution.getFailedTargets()
                    ));
            runTerminalSideEffect("notify completed operation", executionId, () ->
                    notificationService.notifyOperationCompleted(
                            execution.getInitiatedByUserId(),
                            execution.getEnvironment().getName(),
                            execution.getOperationType().name()));
        });
    }

    private void markExecutionPartialSuccess(String executionId) {
        executionRepository.findByIdWithEnvironment(executionId).ifPresent(execution -> {
            if (execution.getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was already cancelled — skipping PARTIAL_SUCCESS transition", executionId);
                return;
            }
            execution.setStatus(ExecutionStatus.PARTIAL_SUCCESS);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            executionRepository.save(execution);
            log.info("Execution {} completed with partial success", executionId);

            runTerminalSideEffect("audit partial-success operation", executionId, () ->
                    auditService.logOperationCompleted(
                            execution.getInitiatedByUserId(),
                            execution.getEnvironment().getEnvironmentId(),
                            execution.getEnvironment().getName(),
                            executionId,
                            execution.getOperationType().name(),
                            execution.getTotalTargets(),
                            execution.getFailedTargets()
                    ));
            runTerminalSideEffect("notify partial-success operation", executionId, () ->
                    notificationService.notifyOperationCompleted(
                            execution.getInitiatedByUserId(),
                            execution.getEnvironment().getName(),
                            execution.getOperationType().name()));
        });
    }

    private void markExecutionFailed(String executionId, String errorMessage) {
        executionRepository.findByIdWithEnvironment(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            execution.setErrorMessage(errorMessage);
            executionRepository.save(execution);
            log.error("Execution {} failed: {}", executionId, errorMessage);

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
                    notificationService.notifyOperationFailed(
                            execution.getInitiatedByUserId(),
                            execution.getEnvironment().getName(),
                            execution.getOperationType().name(),
                            errorMessage));
        });
    }

    private void runTerminalSideEffect(String action, String executionId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for execution {}: {}", action, executionId, e.getMessage());
        }
    }
}
