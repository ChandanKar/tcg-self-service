package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Orchestrates the "Apply" action on a rightsizing recommendation: actually changing a VM's EC2
 * instance type. Only ever called for the recommendation an admin explicitly approved in the UI
 * — never automatic (per the scale-down/scale-up requirements: recommendations are always
 * displayed, never auto-applied).
 */
@Service
public class VmResizeService {

    private static final Logger log = LoggerFactory.getLogger(VmResizeService.class);

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final AwsCloudProviderService awsCloudProviderService;
    private final LockService lockService;
    private final AuditService auditService;
    private final OperationExecutionRepository executionRepository;
    private final CostEstimationService costEstimationService;

    public VmResizeService(VmRepository vmRepository,
                            VmInventorySnapshotRepository vmInventorySnapshotRepository,
                            AwsCloudProviderService awsCloudProviderService,
                            LockService lockService,
                            AuditService auditService,
                            OperationExecutionRepository executionRepository,
                            CostEstimationService costEstimationService) {
        this.costEstimationService = costEstimationService;
        this.executionRepository = executionRepository;
        this.vmRepository = vmRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.awsCloudProviderService = awsCloudProviderService;
        this.lockService = lockService;
        this.auditService = auditService;
    }

    /**
     * Change an AWS VM's instance type (LOW-OPS-RESIZE). Checks the live cloud state (not the
     * database) and that no operation is running in the environment; once AWS has the new type,
     * the request succeeds even if bookkeeping afterwards fails.
     */
    public void applyInstanceTypeChange(String vmId, String targetInstanceType, String userId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("VM", vmId));

        if (vm.getProvider() != CloudProvider.AWS) {
            throw new ValidationException("Instance type changes are only supported for AWS VMs");
        }

        String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        String environmentName = vm.getGroup().getEnvironment().getDisplayName();
        lockService.verifyLockPermission(environmentId, userId);

        if (executionRepository.hasActiveOperations(environmentId)) {
            throw new ValidationException("An operation is running in this environment");
        }

        // Trust AWS, not the database: correct the stored status when they disagree.
        VmStatus liveStatus = awsCloudProviderService.getVmStatus(vm.getProviderVmId(), vm.getRegion());
        if (liveStatus != null && liveStatus != VmStatus.UNKNOWN && liveStatus != vm.getStatus()) {
            vmRepository.updateStatusIfCurrent(vmId, vm.getStatus(), liveStatus, Timestamp.from(Instant.now()));
        }
        if (liveStatus != VmStatus.STOPPED) {
            throw new ValidationException("VM must be stopped before changing its instance type (it is "
                    + (liveStatus != null ? liveStatus : VmStatus.UNKNOWN) + ")");
        }

        // Only the type the page recommends right now (M11, E08-T05): a replayed or edited
        // request cannot resize the VM to anything else.
        boolean matches = costEstimationService.findCandidate(vmId)
                .map(c -> targetInstanceType.equals(c.suggestedInstanceType()))
                .orElse(false);
        if (!matches) {
            throw new ValidationException("Target instance type does not match the current recommendation for this VM");
        }

        String oldInstanceType = vmInventorySnapshotRepository.findByVmVmId(vmId)
                .map(VmInventorySnapshot::getInstanceType)
                .orElse(null);

        auditService.logEnvironmentAction(userId, AuditAction.VM_RESIZE_REQUESTED, environmentId, environmentName,
                "vm", vmId, vm.getDisplayName(),
                "Requested change from " + oldInstanceType + " to " + targetInstanceType);

        try {
            awsCloudProviderService.changeInstanceType(vm.getRegion(), vm.getProviderVmId(), targetInstanceType);
        } catch (RuntimeException e) {
            // The call can fail after AWS applied it (timeout, throttled retry): check before failing.
            String liveType = liveInstanceType(vm);
            if (!targetInstanceType.equals(liveType)) {
                auditService.logEnvironmentFailure(userId, AuditAction.VM_RESIZE_FAILED, environmentId, environmentName,
                        "vm", vmId, vm.getDisplayName(), e.getMessage());
                throw e;
            }
            log.warn("Instance type change for VM {} reported an error but AWS shows {}: treating as done ({})",
                    vmId, liveType, e.getMessage());
        }

        // The type changed: bookkeeping problems from here on are logged, never reported as failure.
        try {
            VmInventorySnapshot snapshot = vmInventorySnapshotRepository.findByVmVmId(vmId).orElseGet(() -> {
                VmInventorySnapshot created = new VmInventorySnapshot();
                created.setVm(vm);
                created.setProvider(vm.getProvider());
                created.setProviderVmId(vm.getProviderVmId());
                return created;
            });
            snapshot.setInstanceType(targetInstanceType);
            snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
            vmInventorySnapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.warn("Instance type of VM {} changed to {} but the inventory snapshot was not updated: {}",
                    vmId, targetInstanceType, e.getMessage());
        }
        auditService.logChange(userId, AuditAction.VM_RESIZE_COMPLETED, "vm", vmId, vm.getDisplayName(),
                oldInstanceType, targetInstanceType, null);
        costEstimationService.invalidateBundles(); // the page must not offer the old recommendation
    }

    private String liveInstanceType(Vm vm) {
        try {
            return awsCloudProviderService.getInstanceType(vm.getRegion(), vm.getProviderVmId());
        } catch (Exception e) {
            log.warn("Could not read the live instance type of VM {}: {}", vm.getVmId(), e.getMessage());
            return null;
        }
    }
}
