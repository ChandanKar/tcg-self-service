package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for VmGroup management operations.
 */
@Service
public class VmGroupService {

    private static final Logger log = LoggerFactory.getLogger(VmGroupService.class);

    private final VmGroupRepository groupRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final DependencyValidator dependencyValidator;

    public VmGroupService(VmGroupRepository groupRepository,
                          EnvironmentRepository environmentRepository,
                          VmRepository vmRepository,
                          DependencyValidator dependencyValidator) {
        this.groupRepository = groupRepository;
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.dependencyValidator = dependencyValidator;
    }

    /**
     * Get all groups for an environment ordered by sequence position.
     */
    public List<VmGroup> getGroupsByEnvironmentId(String environmentId) {
        return groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environmentId);
    }

    /**
     * Get group by ID.
     */
    public VmGroup getGroupById(String groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("VmGroup", groupId));
    }

    /**
     * Get group with VMs eagerly loaded.
     */
    public VmGroup getGroupWithVms(String groupId) {
        return groupRepository.findByIdWithVms(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("VmGroup", groupId));
    }

    /**
     * Create a new group within an environment.
     */
    @Transactional
    public VmGroup createGroup(String environmentId, CreateVmGroupDTO dto) {
        // Verify environment exists
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));

        // Validate name uniqueness within environment
        if (groupRepository.existsByEnvironmentEnvironmentIdAndName(environmentId, dto.getName())) {
            throw new ValidationException("Group with name '" + dto.getName() + "' already exists in this environment");
        }

        // Validate sequence position uniqueness within environment
        if (groupRepository.existsByEnvironmentEnvironmentIdAndSequencePosition(environmentId, dto.getSequencePosition())) {
            throw new ValidationException("Sequence position " + dto.getSequencePosition() + " already exists in this environment");
        }

        // Validate dependencies
        if (dto.getDependsOnGroupIds() != null && !dto.getDependsOnGroupIds().isEmpty()) {
            String newGroupId = UUID.randomUUID().toString();
            dependencyValidator.validateGroupDependencies(environmentId, newGroupId, dto.getDependsOnGroupIds());
        }

        VmGroup group = new VmGroup();
        group.setGroupId(UUID.randomUUID().toString());
        group.setEnvironment(environment);
        group.setName(dto.getName().toLowerCase().replaceAll("\\s+", "-"));
        group.setDisplayName(dto.getDisplayName());
        group.setDescription(dto.getDescription());
        group.setSequencePosition(dto.getSequencePosition());
        group.setDependencies(dto.getDependsOnGroupIds());
        group.setMetadata(dto.getMetadata());

        VmGroup saved = groupRepository.save(group);
        log.info("Created group: {} ({}) in environment {}", saved.getName(), saved.getGroupId(), environmentId);

        return saved;
    }

    /**
     * Update an existing group.
     */
    @Transactional
    public VmGroup updateGroup(String groupId, CreateVmGroupDTO dto) {
        VmGroup group = getGroupById(groupId);
        String environmentId = group.getEnvironment().getEnvironmentId();

        // Validate name uniqueness (if changed)
        if (!group.getName().equals(dto.getName()) &&
                groupRepository.existsByEnvironmentEnvironmentIdAndName(environmentId, dto.getName())) {
            throw new ValidationException("Group with name '" + dto.getName() + "' already exists in this environment");
        }

        // Validate sequence position uniqueness (if changed)
        if (!group.getSequencePosition().equals(dto.getSequencePosition()) &&
                groupRepository.existsByEnvironmentEnvironmentIdAndSequencePosition(environmentId, dto.getSequencePosition())) {
            throw new ValidationException("Sequence position " + dto.getSequencePosition() + " already exists in this environment");
        }

        // Validate dependencies
        if (dto.getDependsOnGroupIds() != null && !dto.getDependsOnGroupIds().isEmpty()) {
            dependencyValidator.validateGroupDependencies(environmentId, groupId, dto.getDependsOnGroupIds());
        }

        group.setName(dto.getName().toLowerCase().replaceAll("\\s+", "-"));
        group.setDisplayName(dto.getDisplayName());
        group.setDescription(dto.getDescription());
        group.setSequencePosition(dto.getSequencePosition());
        group.setDependencies(dto.getDependsOnGroupIds());
        group.setMetadata(dto.getMetadata());

        VmGroup saved = groupRepository.save(group);
        log.info("Updated group: {} ({})", saved.getName(), saved.getGroupId());

        return saved;
    }

    /**
     * Delete a group (only if no VMs exist).
     */
    @Transactional
    public void deleteGroup(String groupId) {
        VmGroup group = getGroupById(groupId);

        // Check if group has VMs
        long vmCount = vmRepository.countByGroupGroupId(groupId);
        if (vmCount > 0) {
            throw new ValidationException("Cannot delete group with existing VMs. Remove VMs first.");
        }

        groupRepository.delete(group);
        log.info("Deleted group: {} ({})", group.getName(), groupId);
    }

    /**
     * Get groups in start order (topologically sorted by dependencies).
     */
    public List<VmGroup> getGroupsInStartOrder(String environmentId) {
        List<VmGroup> allGroups = groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environmentId);
        return dependencyValidator.topologicalSort(allGroups);
    }

    /**
     * Get VM count for a group.
     */
    public int getVmCount(String groupId) {
        return (int) vmRepository.countByGroupGroupId(groupId);
    }

    /**
     * Get running VM count for a group.
     */
    public int getRunningVmCount(String groupId) {
        return (int) vmRepository.countByGroupGroupIdAndStatus(groupId, VmStatus.RUNNING);
    }
}

