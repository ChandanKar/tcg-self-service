package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the "Apply" action on a rightsizing recommendation: actually changing a VM's EC2
 * instance type. Only ever called for the recommendation an admin explicitly approved in the UI
 * — never automatic (per the scale-down/scale-up requirements: recommendations are always
 * displayed, never auto-applied).
 */
@Service
public class VmResizeService {

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final AwsCloudProviderService awsCloudProviderService;
    private final LockService lockService;
    private final AuditService auditService;

    public VmResizeService(VmRepository vmRepository,
                            VmInventorySnapshotRepository vmInventorySnapshotRepository,
                            AwsCloudProviderService awsCloudProviderService,
                            LockService lockService,
                            AuditService auditService) {
        this.vmRepository = vmRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.awsCloudProviderService = awsCloudProviderService;
        this.lockService = lockService;
        this.auditService = auditService;
    }

    public void applyInstanceTypeChange(String vmId, String targetInstanceType, String userId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("VM", vmId));

        if (vm.getProvider() != CloudProvider.AWS) {
            throw new ValidationException("Instance type changes are only supported for AWS VMs");
        }
        if (vm.getStatus() != VmStatus.STOPPED) {
            throw new ValidationException("VM must be stopped before changing its instance type");
        }

        String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        String environmentName = vm.getGroup().getEnvironment().getDisplayName();
        lockService.verifyLockPermission(environmentId, userId);

        String oldInstanceType = vmInventorySnapshotRepository.findByVmVmId(vmId)
                .map(VmInventorySnapshot::getInstanceType)
                .orElse(null);

        auditService.logEnvironmentAction(userId, AuditAction.VM_RESIZE_REQUESTED, environmentId, environmentName,
                "vm", vmId, vm.getDisplayName(),
                "Requested change from " + oldInstanceType + " to " + targetInstanceType);

        try {
            awsCloudProviderService.changeInstanceType(vm.getRegion(), vm.getProviderVmId(), targetInstanceType);

            VmInventorySnapshot snapshot = vmInventorySnapshotRepository.findByVmVmId(vmId)
                    .orElseThrow(() -> new ResourceNotFoundException("VmInventorySnapshot", vmId));
            snapshot.setInstanceType(targetInstanceType);
            vmInventorySnapshotRepository.save(snapshot);

            auditService.logChange(userId, AuditAction.VM_RESIZE_COMPLETED, "vm", vmId, vm.getDisplayName(),
                    oldInstanceType, targetInstanceType, null);
        } catch (Exception e) {
            auditService.logEnvironmentFailure(userId, AuditAction.VM_RESIZE_FAILED, environmentId, environmentName,
                    "vm", vmId, vm.getDisplayName(), e.getMessage());
            throw e;
        }
    }
}
