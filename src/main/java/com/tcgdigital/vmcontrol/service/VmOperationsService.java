package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    public VmOperationsService(OperationExecutionRepository executionRepository,
                               OperationDetailRepository detailRepository,
                               EnvironmentRepository environmentRepository,
                               VmGroupRepository groupRepository,
                               VmRepository vmRepository,
                               DependencyValidator dependencyValidator,
                               CloudProviderFactory cloudProviderFactory,
                               LockService lockService,
                               ObjectMapper objectMapper) {
        this.executionRepository = executionRepository;
        this.detailRepository = detailRepository;
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.dependencyValidator = dependencyValidator;
        this.cloudProviderFactory = cloudProviderFactory;
        this.lockService = lockService;
        this.objectMapper = objectMapper;
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

        // Start async execution
        executeOperationAsync(execution.getExecutionId(), dto.isContinueOnFailure());

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
            // Check if cancelled
            execution = getExecution(executionId);
            if (execution.getStatus() == ExecutionStatus.CANCELLED) {
                log.info("Execution {} was cancelled", executionId);
                return;
            }

            try {
                executeVmOperation(detail, execution.getOperationType());

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

            if (operationType == OperationType.START) {
                result = providerService.startVm(vm.getProviderVmId(), vm.getRegion()).join();
            } else if (operationType == OperationType.STOP) {
                result = providerService.stopVm(vm.getProviderVmId(), vm.getRegion(), false).join();
            } else {
                // RESTART = stop then start
                result = providerService.stopVm(vm.getProviderVmId(), vm.getRegion(), false).join();
                if (result.isSuccess()) {
                    Thread.sleep(5000);
                    result = providerService.startVm(vm.getProviderVmId(), vm.getRegion()).join();
                }
            }

            if (result.isSuccess()) {
                detail.setStatus("completed");
                detail.setCloudOperationId(result.getRequestId());

                // Update VM status
                if (result.getResultStatus() != null) {
                    vm.setStatus(result.getResultStatus());
                    vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
                    vmRepository.save(vm);
                }

                updateExecutionCounters(detail.getExecution().getExecutionId(), true);
            } else {
                detail.setStatus("failed");
                detail.setErrorMessage(result.getMessage());
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

    // ============= Private Helper Methods =============

    private List<Vm> resolveTargetVms(Environment environment, StartOperationDTO dto) {
        List<Vm> targetVms = new ArrayList<>();

        if (dto.getVmIds() != null && !dto.getVmIds().isEmpty()) {
            // Specific VMs requested
            for (String vmId : dto.getVmIds()) {
                vmRepository.findById(vmId).ifPresent(targetVms::add);
            }
        } else if (dto.getGroupIds() != null && !dto.getGroupIds().isEmpty()) {
            // Specific groups requested
            for (String groupId : dto.getGroupIds()) {
                targetVms.addAll(vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId));
            }
        } else {
            // All VMs in environment
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
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.COMPLETED);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            executionRepository.save(execution);
            log.info("Execution {} completed successfully", executionId);
        });
    }

    private void markExecutionPartialSuccess(String executionId) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.PARTIAL_SUCCESS);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            executionRepository.save(execution);
            log.info("Execution {} completed with partial success", executionId);
        });
    }

    private void markExecutionFailed(String executionId, String errorMessage) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setCompletedAt(Timestamp.from(Instant.now()));
            execution.setErrorMessage(errorMessage);
            executionRepository.save(execution);
            log.error("Execution {} failed: {}", executionId, errorMessage);
        });
    }
}
