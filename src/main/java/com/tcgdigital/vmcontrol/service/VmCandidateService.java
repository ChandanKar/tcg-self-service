package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.BatchRegisterVmsDTO;
import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.MoveVmDTO;
import com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.VmCandidateDTO;
import com.tcgdigital.vmcontrol.dto.VmCandidateDTO.Status;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmType;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.support.InstanceRefCodec;
import com.tcgdigital.vmcontrol.service.support.InstanceRefCodec.InstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Add VMs to &lt;environment&gt;" (U11-T01, RegisterVms.dc.html): the instances of an account and region
 * with what each is to the environment, and adding several of them to one group at once.
 */
@Service
public class VmCandidateService {

    private static final Logger log = LoggerFactory.getLogger(VmCandidateService.class);

    public enum Source { EC2, RDS }

    private static final Set<String> STALE_STATES = Set.of("terminated", "shutting-down", "deleting");
    /** The Auto-Discovered group sits at this position (VmDiscoveryService); new groups go before it. */
    private static final int DISCOVERY_GROUP_SEQ = 999;

    private final Ec2Service ec2Service;
    private final RdsCloudProviderService rdsService;
    private final VmRepository vmRepository;
    private final VmGroupRepository groupRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmService vmService;
    private final VmGroupService groupService;
    private final InstanceRefCodec instanceRefs;
    private final SecurityService securityService;
    private final TagReconciliationService tagReconciliationService;

    public VmCandidateService(Ec2Service ec2Service, RdsCloudProviderService rdsService, VmRepository vmRepository,
                              VmGroupRepository groupRepository, EnvironmentRepository environmentRepository,
                              VmService vmService, VmGroupService groupService, InstanceRefCodec instanceRefs,
                              SecurityService securityService, TagReconciliationService tagReconciliationService) {
        this.ec2Service = ec2Service;
        this.rdsService = rdsService;
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.environmentRepository = environmentRepository;
        this.vmService = vmService;
        this.groupService = groupService;
        this.instanceRefs = instanceRefs;
        this.securityService = securityService;
        this.tagReconciliationService = tagReconciliationService;
    }

    // ------------------------------------------------------------------ candidates

    /** The instances (or RDS databases) of the account and region, each with its status for the environment. */
    @Transactional(readOnly = true)
    public List<VmCandidateDTO> listCandidates(String environmentId, Source source, String region, String accountRef,
                                               String userId) {
        Environment environment = environment(environmentId);
        if (source == Source.RDS) {
            if (!rdsService.isEnabled()) {
                throw new ResourceNotFoundException("RDS support is not enabled");
            }
            List<RdsDatabaseInfoDTO> databases = rdsService.listDatabases(CloudTarget.of(accountRef, region));
            Map<String, Vm> known = registered(CloudProvider.AWS_RDS,
                    databases.stream().map(RdsDatabaseInfoDTO::providerVmId).toList());
            return databases.stream().map(db -> candidate(environment, CloudProvider.AWS_RDS, accountRef, region, userId,
                    db.providerVmId(), db.identifier(), db.instanceClass(), null, null, db.status(),
                    known.get(db.providerVmId()), db.stoppable() ? null : db.notStoppableReason())).toList();
        }
        List<Ec2InstanceInfo> instances = ec2Service.listInstances(accountRef, region);
        Map<String, Vm> known = registered(CloudProvider.AWS, instances.stream().map(Ec2InstanceInfo::instanceId).toList());
        return instances.stream().map(i -> {
            String name = i.tags() == null ? null : i.tags().get("Name");
            return candidate(environment, CloudProvider.AWS, accountRef, region, userId, i.instanceId(),
                    name == null || name.isBlank() ? null : name, i.instanceType(), i.availabilityZone(),
                    i.privateIpAddress(), i.state(), known.get(i.instanceId()), null);
        }).toList();
    }

    private Map<String, Vm> registered(CloudProvider provider, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return vmRepository.findByProviderVmIdsFetchEnvironment(provider, ids).stream()
                .collect(Collectors.toMap(Vm::getProviderVmId, Function.identity(), (a, b) -> a));
    }

    private VmCandidateDTO candidate(Environment environment, CloudProvider provider, String accountRef, String region,
                                     String userId, String providerVmId, String name, String instanceType, String zone,
                                     String privateIp, String state, Vm vm, String availableNote) {
        Status status = Status.AVAILABLE;
        String inEnvironment = null;
        String inGroup = null;
        String note = availableNote;
        if (vm != null) {
            Environment where = vm.getGroup().getEnvironment();
            boolean visible = where.getEnvironmentId().equals(environment.getEnvironmentId())
                    || securityService.canViewEnvironment(where.getEnvironmentId());
            inEnvironment = visible ? label(where) : "another environment";
            inGroup = visible ? groupLabel(vm.getGroup()) : null;
            if (!Boolean.TRUE.equals(vm.getIsActive())) {
                status = Status.REMOVED;
                note = "Removed from the platform; reactivate it from the registry";
            } else if (state != null && STALE_STATES.contains(state.toLowerCase())) {
                status = Status.STALE;
                note = "In " + inEnvironment + ", but AWS reports it " + state;
            } else if (Boolean.TRUE.equals(vm.getDiscoveryPending())
                    && where.getEnvironmentId().equals(environment.getEnvironmentId())) {
                status = Status.DISCOVERED_UNGROUPED;
                note = "Found by discovery, not in a group";
            } else {
                status = Status.IN_ENVIRONMENT;
                note = "Already in " + inEnvironment;
            }
        }
        return new VmCandidateDTO(instanceRefs.encode(provider, accountRef, region, providerVmId, userId), providerVmId,
                name, instanceType, zone, privateIp, state, status, inEnvironment, inGroup, note);
    }

    // ------------------------------------------------------------------ batch

    /**
     * Adds the picked instances to one group, after its current VMs, all or nothing. A VM that
     * discovery found in this environment and that waits for a group is moved there instead; any
     * instance already in an environment (or removed) refuses the whole batch, naming each one.
     */
    @Transactional
    public List<Vm> registerBatch(String environmentId, BatchRegisterVmsDTO dto, String actorUserId) {
        Environment environment = environment(environmentId);
        boolean hasGroup = dto.groupId() != null && !dto.groupId().isBlank();
        boolean hasNewGroup = dto.newGroupName() != null && !dto.newGroupName().isBlank();
        if (hasGroup == hasNewGroup) {
            throw new ValidationException("Pick a group, or name a new one");
        }

        // Resolve every reference first: nothing is written until the whole batch is known to fit.
        record Pick(BatchRegisterVmsDTO.Item item, InstanceRef ref, Vm discovered) {
        }
        List<Pick> picks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BatchRegisterVmsDTO.Item item : dto.items()) {
            InstanceRef ref = instanceRefs.decode(item.ref(), actorUserId);
            String name = item.name().trim();
            if (!seen.add(ref.provider() + "|" + ref.instanceId())) {
                problems.add(name + " is picked twice");
                continue;
            }
            Optional<Vm> existing = vmRepository.findByProviderAndProviderVmId(ref.provider(), ref.instanceId());
            Vm discovered = null;
            if (existing.isPresent()) {
                Vm vm = existing.get();
                Environment where = vm.getGroup().getEnvironment();
                boolean here = where.getEnvironmentId().equals(environmentId);
                if (!Boolean.TRUE.equals(vm.getIsActive())) {
                    problems.add(name + " was removed from the platform; reactivate it from the registry");
                } else if (here && Boolean.TRUE.equals(vm.getDiscoveryPending())) {
                    discovered = vm;
                } else {
                    boolean visible = here || securityService.canViewEnvironment(where.getEnvironmentId());
                    problems.add(name + " is already in " + (visible ? label(where) : "another environment"));
                }
            }
            picks.add(new Pick(item, ref, discovered));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(String.join("; ", problems) + ". Nothing was added.");
        }

        VmGroup group = hasGroup ? group(environmentId, dto.groupId()) : newGroup(environment, dto.newGroupName().trim(), actorUserId);
        int next = Optional.ofNullable(vmRepository.findMaxSequencePositionByGroupId(group.getGroupId())).orElse(0) + 1;
        List<Vm> added = new ArrayList<>();
        for (Pick pick : picks) {
            if (pick.discovered() != null) {
                MoveVmDTO move = new MoveVmDTO();
                move.setTargetGroupId(group.getGroupId());
                move.setSequencePosition(next++);
                added.add(vmService.moveVm(pick.discovered().getVmId(), move, actorUserId));
                continue;
            }
            RegisterVmDTO register = new RegisterVmDTO();
            register.setGroupId(group.getGroupId());
            register.setName(pick.item().name().trim());
            register.setDisplayName(pick.item().name().trim());
            register.setProvider(pick.ref().provider());
            register.setRegion(pick.ref().region());
            register.setProviderVmId(pick.ref().instanceId());
            register.setAwsAccountRef(pick.ref().accountRef());
            register.setVmType(VmType.DEV);
            register.setSequencePosition(next++);
            // The id was resolved here from a reference issued to this user, so it is not typed.
            added.add(vmService.registerVm(register, actorUserId, true));
        }
        log.info("Added {} VMs to group {} of environment {} by {}", added.size(), group.getName(),
                environment.getName(), actorUserId);
        return added;
    }

    /** Tags the environment's new VMs for cost allocation; never fails the request (run after the batch commits). */
    public void tagNewVmsSafely(String environmentId) {
        try {
            environmentRepository.findById(environmentId).ifPresent(tagReconciliationService::reconcileNewInEnvironment);
        } catch (Exception e) {
            log.warn("Tag reconciliation after adding VMs to {} failed: {}", environmentId, e.getMessage());
        }
    }

    private VmGroup group(String environmentId, String groupId) {
        VmGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ValidationException("That group no longer exists"));
        if (!group.getEnvironment().getEnvironmentId().equals(environmentId)) {
            throw new ValidationException("That group is in another environment");
        }
        return group;
    }

    /** A new group at the end of the start order (before Auto-Discovered). */
    private VmGroup newGroup(Environment environment, String name, String actorUserId) {
        int last = groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environment.getEnvironmentId())
                .stream().mapToInt(g -> g.getSequencePosition() == null ? 0 : g.getSequencePosition())
                .filter(p -> p < DISCOVERY_GROUP_SEQ).max().orElse(0);
        CreateVmGroupDTO create = new CreateVmGroupDTO();
        create.setName(name);
        create.setDisplayName(name);
        create.setSequencePosition(last + 1);
        return groupService.createGroup(environment.getEnvironmentId(), create, actorUserId);
    }

    private Environment environment(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }

    private static String label(Environment environment) {
        return environment.getDisplayName() == null || environment.getDisplayName().isBlank()
                ? environment.getName() : environment.getDisplayName();
    }

    private static String groupLabel(VmGroup group) {
        return group.getDisplayName() == null || group.getDisplayName().isBlank() ? group.getName() : group.getDisplayName();
    }
}
