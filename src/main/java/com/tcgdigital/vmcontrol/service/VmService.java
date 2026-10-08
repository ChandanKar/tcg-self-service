package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for VM management operations.
 */
@Service
public class VmService {

    private static final Logger log = LoggerFactory.getLogger(VmService.class);

    private final VmRepository vmRepository;
    private final VmGroupRepository groupRepository;
    private final DependencyValidator dependencyValidator;
    private final AuditService auditService;
    private final CloudProviderFactory cloudProviderFactory;

    public VmService(VmRepository vmRepository,
                     VmGroupRepository groupRepository,
                     DependencyValidator dependencyValidator,
                     AuditService auditService,
                     CloudProviderFactory cloudProviderFactory) {
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.dependencyValidator = dependencyValidator;
        this.auditService = auditService;
        this.cloudProviderFactory = cloudProviderFactory;
    }

    /**
     * Get all VMs in an environment.
     */
    public List<Vm> getVmsByEnvironmentId(String environmentId) {
        return vmRepository.findByEnvironmentId(environmentId);
    }

    /**
     * Get all VMs in a group.
     */
    public List<Vm> getVmsByGroupId(String groupId) {
        return vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId);
    }

    /**
     * Get a page of VMs in a group, for scalable VM listing UIs.
     */
    public Page<Vm> getVmsByGroupIdPaged(String groupId, int page, int size) {
        return vmRepository.findByGroupGroupIdAndIsActiveTrueOrderBySequencePositionAsc(groupId, PageRequest.of(page, size));
    }

    /**
     * VM/running counts for every group in an environment, keyed by groupId — computed with a
     * single query so a group listing doesn't need a pair of count queries per group.
     */
    public Map<String, VmRepository.GroupVmCounts> getVmCountsByGroupForEnvironment(String environmentId) {
        return vmRepository.countVmsGroupedByGroup(environmentId, VmStatus.RUNNING).stream()
                .collect(Collectors.toMap(VmRepository.GroupVmCounts::getGroupId, c -> c));
    }

    /**
     * Get VM by ID.
     */
    public Vm getVmById(String vmId) {
        return vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("Vm", vmId));
    }

    /**
     * Register a new VM in a group.
     */
    @Transactional
    public Vm registerVm(RegisterVmDTO dto) {
        // Verify group exists
        VmGroup group = groupRepository.findById(dto.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("VmGroup", dto.getGroupId()));

        // Validate name uniqueness within group
        if (vmRepository.existsByGroupGroupIdAndName(dto.getGroupId(), dto.getName())) {
            throw new ValidationException("VM with name '" + dto.getName() + "' already exists in this group");
        }

        // Validate sequence position uniqueness within group
        if (vmRepository.existsByGroupGroupIdAndSequencePosition(dto.getGroupId(), dto.getSequencePosition())) {
            throw new ValidationException("Sequence position " + dto.getSequencePosition() + " already exists in this group");
        }

        // Validate provider VM ID uniqueness
        if (vmRepository.existsByProviderAndProviderVmId(dto.getProvider(), dto.getProviderVmId())) {
            throw new ValidationException("VM with provider ID '" + dto.getProviderVmId() + "' is already registered");
        }

        // Validate VM dependencies
        if (dto.getDependsOnVmIds() != null && !dto.getDependsOnVmIds().isEmpty()) {
            String newVmId = UUID.randomUUID().toString();
            dependencyValidator.validateVmDependencies(dto.getGroupId(), newVmId, dto.getDependsOnVmIds());
        }

        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(dto.getName().toLowerCase().replaceAll("\\s+", "-"));
        vm.setDisplayName(dto.getDisplayName());
        vm.setDescription(dto.getDescription());
        vm.setPurpose(dto.getPurpose());
        vm.setRemarks(dto.getRemarks());
        vm.setProvider(dto.getProvider());
        vm.setRegion(dto.getRegion());
        vm.setProviderVmId(dto.getProviderVmId());
        vm.setVmType(dto.getVmType());
        vm.setSequencePosition(dto.getSequencePosition());
        vm.setDependencies(dto.getDependsOnVmIds());
        vm.setMetadata(dto.getMetadata());
        vm.setStatus(VmStatus.UNKNOWN);
        vm.setIsActive(true);  // New VMs are active by default

        Vm saved = vmRepository.save(vm);
        log.info("Registered VM: {} ({}) in group {}", saved.getName(), saved.getVmId(), dto.getGroupId());

        return saved;
    }

    /**
     * Update VM status.
     */
    @Transactional
    public Vm updateVmStatus(String vmId, VmStatus status) {
        Vm vm = getVmById(vmId);
        vm.setStatus(status);
        vm.setLastStateSyncAt(new java.sql.Timestamp(System.currentTimeMillis()));
        return vmRepository.save(vm);
    }

    /**
     * Update VM details.
     */
    @Transactional
    public Vm updateVm(String vmId, RegisterVmDTO dto) {
        Vm vm = getVmById(vmId);
        String groupId = vm.getGroup().getGroupId();

        // Validate name uniqueness (if changed)
        if (!vm.getName().equals(dto.getName()) &&
                vmRepository.existsByGroupGroupIdAndName(groupId, dto.getName())) {
            throw new ValidationException("VM with name '" + dto.getName() + "' already exists in this group");
        }

        // Validate sequence position uniqueness (if changed)
        if (!vm.getSequencePosition().equals(dto.getSequencePosition()) &&
                vmRepository.existsByGroupGroupIdAndSequencePosition(groupId, dto.getSequencePosition())) {
            throw new ValidationException("Sequence position " + dto.getSequencePosition() + " already exists in this group");
        }

        // Validate VM dependencies
        if (dto.getDependsOnVmIds() != null && !dto.getDependsOnVmIds().isEmpty()) {
            dependencyValidator.validateVmDependencies(groupId, vmId, dto.getDependsOnVmIds());
        }

        vm.setName(dto.getName().toLowerCase().replaceAll("\\s+", "-"));
        vm.setDisplayName(dto.getDisplayName());
        vm.setDescription(dto.getDescription());
        vm.setPurpose(dto.getPurpose());
        vm.setRemarks(dto.getRemarks());
        vm.setProvider(dto.getProvider());
        vm.setRegion(dto.getRegion());
        vm.setProviderVmId(dto.getProviderVmId());
        vm.setVmType(dto.getVmType());
        vm.setSequencePosition(dto.getSequencePosition());
        vm.setDependencies(dto.getDependsOnVmIds());
        vm.setMetadata(dto.getMetadata());

        Vm saved = vmRepository.save(vm);
        log.info("Updated VM: {} ({})", saved.getName(), saved.getVmId());

        return saved;
    }

    /**
     * Delete a VM (unregister from platform).
     */
    @Transactional
    public void deleteVm(String vmId) {
        Vm vm = getVmById(vmId);

        // Check if any VMs depend on this VM
        List<Vm> allVmsInGroup = vmRepository.findByGroupId(vm.getGroup().getGroupId());
        for (Vm otherVm : allVmsInGroup) {
            if (otherVm.getDependencies().contains(vmId)) {
                throw new ValidationException("Cannot delete VM: VM '" + otherVm.getName() + "' depends on it");
            }
        }

        vmRepository.delete(vm);
        log.info("Deleted VM: {} ({})", vm.getName(), vmId);
    }

    /**
     * Acknowledge an auto-discovered VM — clears discovery_pending flag.
     * Admin calls this after reviewing and placing the VM in the correct group.
     */
    @Transactional
    public Vm acknowledgeVm(String vmId, String userId) {
        Vm vm = getVmById(vmId);
        if (!Boolean.TRUE.equals(vm.getDiscoveryPending())) {
            throw new ValidationException("VM '" + vm.getName() + "' is not pending discovery review");
        }
        vm.setDiscoveryPending(false);
        vmRepository.save(vm);
        auditService.logVmRegistered(
                userId,
                vm.getGroup().getEnvironment().getEnvironmentId(),
                vm.getGroup().getEnvironment().getName(),
                vmId,
                vm.getName());
        log.info("VM {} acknowledged by user {} — no longer pending review", vm.getName(), userId);
        return vm;
    }

    /**
     * Bring back a VM that state sync deactivated (M7), e.g. after its region was corrected.
     * The cloud is asked first (outside any transaction); the VM comes back with the status
     * the cloud reports, or the call fails if the instance still cannot be found.
     */
    public Vm reactivateVm(String vmId, String userId) {
        Vm vm = vmRepository.findByIdFetchGroupAndEnvironment(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
        if (Boolean.TRUE.equals(vm.getIsActive())) {
            throw new ValidationException("VM '" + vm.getName() + "' is already active");
        }
        VmStatus cloudStatus;
        try {
            CloudProviderService provider = cloudProviderFactory.getService(vm.getProvider());
            cloudStatus = provider == null || !provider.isAvailable() ? null
                    : provider.getVmStatus(vm.getProviderVmId(), vm.getRegion());
        } catch (Exception e) {
            log.warn("Could not read cloud status of VM {} for reactivation: {}", vmId, e.getMessage());
            cloudStatus = null;
        }
        if (cloudStatus == VmStatus.NOT_FOUND || cloudStatus == VmStatus.TERMINATED) {
            throw new ValidationException("Instance not found in " + vm.getRegion() + " - fix the region first");
        }
        if (cloudStatus == null || cloudStatus == VmStatus.UNKNOWN) {
            throw new ValidationException("Could not read the instance's status from the cloud; try again later");
        }
        if (vmRepository.reactivateIfInactive(vmId, cloudStatus, false, java.sql.Timestamp.from(java.time.Instant.now())) == 0) {
            throw new ValidationException("VM '" + vm.getName() + "' is already active");
        }
        auditService.logEnvironmentAction(userId, AuditAction.VM_REACTIVATED,
                vm.getGroup().getEnvironment().getEnvironmentId(), vm.getGroup().getEnvironment().getName(),
                "vm", vmId, vm.getName(), "Reactivated; cloud status " + cloudStatus);
        log.info("VM {} reactivated by {} with status {}", vm.getName(), userId, cloudStatus);
        return vmRepository.findByIdFetchGroupAndEnvironment(vmId).orElseThrow();
    }

    /**
     * Get VMs with state drift detected.
     */
    public List<Vm> getVmsWithStateDrift() {
        return vmRepository.findByStateDriftDetectedTrue();
    }

    /**
     * Get VMs in start order for a group (respecting dependencies).
     */
    public List<List<Vm>> getVmStartBatches(String groupId) {
        return dependencyValidator.getVmStartBatches(groupId);
    }
}

