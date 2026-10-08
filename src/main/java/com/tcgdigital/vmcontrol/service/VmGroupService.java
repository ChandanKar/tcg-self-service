package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.support.NameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final EnvironmentAccessRepository accessRepository;
    private final EnvironmentAccessRequestRepository requestRepository;
    private final AutomationRuleRepository automationRuleRepository;
    private final AuditService auditService;

    public VmGroupService(VmGroupRepository groupRepository,
                          EnvironmentRepository environmentRepository,
                          VmRepository vmRepository,
                          DependencyValidator dependencyValidator,
                          EnvironmentAccessRepository accessRepository,
                          EnvironmentAccessRequestRepository requestRepository,
                          AutomationRuleRepository automationRuleRepository,
                          AuditService auditService) {
        this.groupRepository = groupRepository;
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.dependencyValidator = dependencyValidator;
        this.accessRepository = accessRepository;
        this.requestRepository = requestRepository;
        this.automationRuleRepository = automationRuleRepository;
        this.auditService = auditService;
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

        // Validate name uniqueness within environment, on the name as stored (M3)
        String name = NameNormalizer.slug(dto.getName());
        if (groupRepository.existsByEnvironmentEnvironmentIdAndName(environmentId, name)) {
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
        group.setName(name);
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

        // The name is the group's identity (EKS sync matches node groups by it): it cannot change (M4).
        if (dto.getName() != null && !group.getName().equals(NameNormalizer.slug(dto.getName()))) {
            throw new ValidationException("Group name cannot be changed; edit the display name");
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

        group.setDisplayName(dto.getDisplayName());
        if (dto.getDescription() != null) {
            group.setDescription(dto.getDescription().isBlank() ? null : dto.getDescription().trim());
        }
        group.setSequencePosition(dto.getSequencePosition());
        group.setDependencies(dto.getDependsOnGroupIds());
        if (dto.getMetadata() != null) {
            // A patch: EKS identity keys (clusterName, nodeGroupName, region) are kept (M4).
            group.setMetadata(com.tcgdigital.vmcontrol.service.support.JsonMetadataMerger.merge(
                    group.getMetadata(), dto.getMetadata()));
        }

        VmGroup saved = groupRepository.save(group);
        log.info("Updated group: {} ({})", saved.getName(), saved.getGroupId());

        return saved;
    }

    /**
     * Delete a group (M2). Refused while it holds VMs, including removed or inactive ones: the
     * FK cascade would hard-delete them with their history. Other groups stop depending on it,
     * and grants, pending requests and automation rules scoped to it are revoked, cancelled and
     * disabled, all in this transaction.
     */
    @Transactional
    public void deleteGroup(String groupId, String userId) {
        VmGroup group = getGroupById(groupId);
        Environment environment = group.getEnvironment();

        long active = vmRepository.countByGroupGroupId(groupId);
        if (active > 0) {
            throw new ValidationException("Cannot delete group with existing VMs. Remove VMs first.");
        }
        long all = vmRepository.countAllByGroupId(groupId);
        if (all > active) {
            throw new ValidationException("Group has " + (all - active) + " removed VM(s) whose history would be lost; "
                    + "move them to another group first");
        }

        // Other groups must not keep a dependency on a group that no longer exists.
        for (VmGroup other : groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environment.getEnvironmentId())) {
            List<String> deps = other.getDependencies();
            if (!other.getGroupId().equals(groupId) && deps != null && deps.contains(groupId)) {
                other.setDependencies(deps.stream().filter(d -> !d.equals(groupId)).collect(Collectors.toList()));
                groupRepository.save(other);
                auditService.logEnvironmentAction(userId, AuditAction.GROUP_DEPENDENCIES_UPDATED,
                        environment.getEnvironmentId(), environment.getName(), "vm_group", other.getGroupId(),
                        other.getName(), "Dependency on deleted group '" + group.getName() + "' removed");
            }
        }

        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<EnvironmentAccess> grants = accessRepository.findByScopeTypeAndScopeIdAndStatus(
                AccessScopeType.GROUP, groupId, AccessStatus.ACTIVE);
        grants.forEach(EnvironmentAccess::revoke);
        accessRepository.saveAll(grants);
        int cancelled = 0;
        for (EnvironmentAccessRequest request : requestRepository.findByScopeTypeAndScopeIdAndStatus(
                AccessScopeType.GROUP, groupId, AccessRequestStatus.PENDING)) {
            cancelled += requestRepository.cancelIfPending(request.getRequestId(), now);
        }
        int rules = 0;
        for (AutomationRule rule : automationRuleRepository.findByScopeTypeAndScopeId(AutomationScopeType.GROUP, groupId)) {
            if (Boolean.TRUE.equals(rule.getEnabled())) {
                rule.setEnabled(false);
                rule.setDisabledReason("Target group no longer exists");
                automationRuleRepository.save(rule);
                rules++;
            }
        }

        groupRepository.delete(group);
        auditService.logEnvironmentAction(userId, AuditAction.GROUP_DELETED, environment.getEnvironmentId(),
                environment.getName(), "vm_group", groupId, group.getName(),
                String.format("Group deleted; %d grant(s) revoked, %d request(s) cancelled, %d automation rule(s) disabled",
                        grants.size(), cancelled, rules));
        log.info("Deleted group: {} ({}) - {} grants revoked, {} requests cancelled, {} rules disabled",
                group.getName(), groupId, grants.size(), cancelled, rules);
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

