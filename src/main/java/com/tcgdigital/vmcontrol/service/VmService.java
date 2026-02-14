package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for VM management operations.
 */
@Service
public class VmService {

    private static final Logger log = LoggerFactory.getLogger(VmService.class);

    private final VmRepository vmRepository;
    private final VmGroupRepository groupRepository;
    private final DependencyValidator dependencyValidator;

    public VmService(VmRepository vmRepository,
                     VmGroupRepository groupRepository,
                     DependencyValidator dependencyValidator) {
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.dependencyValidator = dependencyValidator;
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
        vm.setProvider(dto.getProvider());
        vm.setRegion(dto.getRegion());
        vm.setProviderVmId(dto.getProviderVmId());
        vm.setVmType(dto.getVmType());
        vm.setSequencePosition(dto.getSequencePosition());
        vm.setDependencies(dto.getDependsOnVmIds());
        vm.setMetadata(dto.getMetadata());
        vm.setStatus(VmStatus.UNKNOWN);

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

