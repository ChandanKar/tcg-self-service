package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.EksClusterInfoDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.eks.model.Nodegroup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Syncs EKS clusters and their node groups into the DB.
 *
 * Mapping:
 *   EKS cluster   → Environment  (serviceType="EKS", name=clusterName)
 *   EKS node group → VmGroup      (name=nodeGroupName)
 *   node group unit → Vm          (provider=AWS_EKS, providerVmId="{cluster}/{nodeGroup}")
 *
 * Region is read from Environment.metadata JSON key "region"; falls back to aws.region property.
 */
@Service
public class EksSyncService {

    private static final Logger log = LoggerFactory.getLogger(EksSyncService.class);

    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;
    private final EksCloudProviderService eksService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    @Value("${eks.sync.regions:}")
    private String configuredSyncRegions;

    // Self-reference via the Spring proxy so the per-node-group @Transactional(REQUIRES_NEW)
    // boundaries below actually take effect (a direct this.method() call bypasses them).
    @Lazy
    @Autowired
    private EksSyncService self;

    public EksSyncService(EnvironmentRepository environmentRepository,
                          VmGroupRepository groupRepository,
                          VmRepository vmRepository,
                          EksCloudProviderService eksService,
                          AuditService auditService,
                          NotificationService notificationService,
                          ObjectMapper objectMapper) {
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.eksService = eksService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns EKS clusters that exist in AWS but are not yet registered in the DB, scanning
     * every region in {@code eks.sync.regions} (or the {@code aws.region} default) — the same
     * region set {@link #autoDiscoverClusters()} uses, so what an admin sees in the "create
     * environment from EKS" picker matches what auto-discovery would find.
     */
    public List<EksClusterInfoDTO> getUnregisteredEksClusters() {
        List<EksClusterInfoDTO> result = new ArrayList<>();
        for (String region : resolveSyncRegions()) {
            try {
                eksService.listClusters(region).stream()
                        .filter(name -> !environmentRepository.existsByName(name))
                        .forEach(name -> result.add(new EksClusterInfoDTO(name, region)));
            } catch (Exception e) {
                log.error("Failed to list EKS clusters in region {}: {}", region, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Does everything: auto-discovers and registers unregistered clusters, then refreshes
     * status for every already-registered EKS environment. Used by the manual "sync now" admin
     * action, which — being an explicit, human-initiated request — always does both regardless
     * of the {@code eks.sync.auto-register.enabled} / {@code eks.sync.status-refresh.enabled}
     * flags that gate {@link com.tcgdigital.vmcontrol.scheduler.EksSyncScheduler}'s automatic
     * cycle.
     * @return total number of node groups synced across all clusters
     */
    public int syncAllEksClusters() {
        if (!eksService.isAvailable()) {
            log.warn("EKS cloud provider not available — skipping EKS sync");
            return -1;
        }

        autoDiscoverClusters();
        return syncRegisteredEksEnvironments();
    }

    /**
     * Refreshes node-group status/drift for every already-registered EKS environment, without
     * touching cluster discovery. Split out from {@link #syncAllEksClusters()} so the scheduler
     * can run this on its own schedule independent of auto-registration.
     * @return total number of node groups synced across all environments
     */
    public int syncRegisteredEksEnvironments() {
        if (!eksService.isAvailable()) {
            log.warn("EKS cloud provider not available — skipping EKS status refresh");
            return -1;
        }

        List<Environment> eksEnvironments = environmentRepository.findActiveEksEnvironments();
        log.info("EKS sync starting for {} EKS environment(s)", eksEnvironments.size());

        int total = 0;
        for (Environment env : eksEnvironments) {
            try {
                total += syncEksEnvironment(env);
            } catch (Exception e) {
                log.error("Failed to sync EKS environment {}: {}", env.getName(), e.getMessage(), e);
            }
        }

        log.info("EKS sync complete — {} node groups processed", total);
        return total;
    }

    /**
     * Discovers EKS clusters in AWS that have no corresponding Environment record in the DB,
     * across every region configured in eks.sync.regions (falling back to aws.region if unset).
     * Creates an Environment (serviceType=EKS) for each new cluster found.
     */
    @Transactional
    public void autoDiscoverClusters() {
        for (String region : resolveSyncRegions()) {
            autoDiscoverClustersInRegion(region);
        }
    }

    private void autoDiscoverClustersInRegion(String region) {
        List<String> clusterNames;
        try {
            clusterNames = eksService.listClusters(region);
        } catch (Exception e) {
            log.error("Failed to list EKS clusters in region {} during auto-discovery: {}", region, e.getMessage());
            return;
        }

        if (clusterNames.isEmpty()) {
            log.info("No EKS clusters found in region {} during auto-discovery", region);
            return;
        }

        log.info("EKS auto-discovery found {} cluster(s) in region {}: {}", clusterNames.size(), region, clusterNames);

        for (String clusterName : clusterNames) {
            if (environmentRepository.existsByName(clusterName)) {
                continue;
            }
            try {
                Environment env = new Environment();
                env.setEnvironmentId(UUID.randomUUID().toString());
                env.setName(clusterName);
                env.setDisplayName(clusterName);
                env.setDescription("Auto-discovered EKS cluster");
                env.setServiceType("EKS");
                env.setIsActive(true);
                env.setMetadata("{\"region\":\"" + region + "\"}");
                environmentRepository.save(env);
                log.info("Auto-registered EKS environment for cluster: {}", clusterName);
                auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "environment",
                        env.getEnvironmentId(), clusterName,
                        "EKS cluster auto-discovered and registered in region " + region);
                notifyEksChanges(env, new EksSyncChanges(1, 0, 0));
            } catch (Exception e) {
                log.error("Failed to auto-register EKS cluster {}: {}", clusterName, e.getMessage());
            }
        }
    }

    /**
     * Parses eks.sync.regions (comma-separated) into a distinct region list,
     * falling back to the single aws.region default when unset.
     */
    private List<String> resolveSyncRegions() {
        if (configuredSyncRegions != null && !configuredSyncRegions.isBlank()) {
            List<String> regions = Arrays.stream(configuredSyncRegions.split(","))
                    .map(String::trim)
                    .filter(r -> !r.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
            if (!regions.isEmpty()) {
                return regions;
            }
        }
        return List.of(defaultRegion);
    }

    /**
     * Syncs a single EKS environment: discovers node groups and upserts VmGroups + Vms.
     * <p>Deliberately <b>not</b> {@code @Transactional} — each node group is upserted (and each
     * stale group deactivated) in its own {@code REQUIRES_NEW} transaction via {@link #self},
     * so a constraint violation or transient DB error on one node group can't roll back every
     * other node group's changes for this environment.
     * @return number of node groups synced
     */
    public int syncEksEnvironment(Environment environment) {
        String clusterName = environment.getName();
        String region = resolveRegion(environment);

        log.info("Syncing EKS cluster '{}' in region '{}'", clusterName, region);

        List<String> liveNodeGroups;
        try {
            liveNodeGroups = eksService.listNodegroups(clusterName, region);
        } catch (Exception e) {
            log.error("Failed to list node groups for EKS cluster '{}' in region '{}' — skipping this sync cycle: {}",
                    clusterName, region, e.getMessage());
            return 0;
        }

        if (liveNodeGroups.isEmpty()) {
            log.warn("No node groups returned for EKS cluster '{}' — skipping (cluster may not exist or credentials insufficient)", clusterName);
            return 0;
        }

        Set<String> liveNames = new HashSet<>(liveNodeGroups);
        EksSyncChanges changes = new EksSyncChanges();

        // Next sequence position for any newly-discovered group, derived from what's actually
        // persisted (not from this cycle's list order) — VmGroup rows are never deleted, so a
        // position based purely on list index can collide with one already reserved by an
        // existing (or long-removed) group and silently fail every cycle thereafter.
        AtomicInteger nextSequence = new AtomicInteger(resolveNextSequencePosition(environment.getEnvironmentId()));

        // Upsert VmGroup + Vm for each live node group — each in its own transaction, so one
        // bad node group doesn't discard the rest (or the newly-added one).
        for (String nodeGroupName : liveNodeGroups) {
            try {
                changes.add(self.upsertNodeGroup(environment, clusterName, nodeGroupName, region, nextSequence));
            } catch (Exception e) {
                log.error("Failed to upsert node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage(), e);
                changes.failed++;
                changes.failedNames.add(nodeGroupName);
                try {
                    auditService.logAction(null, AuditAction.EKS_NODEGROUP_SYNC_FAILED, "vm_group", null,
                            clusterName + "/" + nodeGroupName, "EKS node group sync failed: " + e.getMessage());
                } catch (Exception auditEx) {
                    log.warn("Could not audit EKS node group sync failure for {}/{}: {}",
                            clusterName, nodeGroupName, auditEx.getMessage());
                }
            }
        }

        // Deactivate VmGroups (and their Vms) that no longer exist in the cluster — again one
        // transaction per group.
        List<VmGroup> existingGroups = groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(
                environment.getEnvironmentId());
        for (VmGroup group : existingGroups) {
            if (!liveNames.contains(group.getName())) {
                try {
                    changes.removed += self.deactivateNodeGroup(group, clusterName);
                } catch (Exception e) {
                    log.error("Failed to deactivate removed EKS node group {}/{}: {}",
                            clusterName, group.getName(), e.getMessage());
                }
            }
        }

        notifyEksChanges(environment, changes);
        return liveNodeGroups.size();
    }

    /**
     * Manually re-syncs a single EKS environment on demand, looked up by id. Used by the
     * "resync this environment now" admin action — a node group added to AWS since the last
     * scheduled cycle (or one that failed to sync, e.g. due to a since-fixed collision) doesn't
     * have to wait for the next scheduled run.
     * <p>Not {@code @Transactional} for the same reason as {@link #syncEksEnvironment} — the
     * per-node-group commits must be independent.
     */
    public int syncEksEnvironmentById(String environmentId) {
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found: " + environmentId));
        if (!"EKS".equalsIgnoreCase(environment.getServiceType())) {
            throw new ValidationException("Environment '" + environment.getName() + "' is not an EKS environment");
        }
        return syncEksEnvironment(environment);
    }

    // ---- private helpers ----

    /**
     * Next sequence position safe to assign to a newly-discovered node group, derived from
     * the actual persisted max (not this cycle's list order) so it can never collide with an
     * already-reserved position — see the comment in syncEksEnvironment for why that matters.
     */
    private int resolveNextSequencePosition(String environmentId) {
        Integer max = groupRepository.findMaxSequencePositionByEnvironmentId(environmentId);
        return (max != null ? max : 0) + 1;
    }

    /**
     * Upserts one node group's {@code VmGroup} + {@code Vm} in its own transaction — public and
     * called via {@link #self} so {@code REQUIRES_NEW} applies. A failure here rolls back only
     * this node group.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EksSyncChanges upsertNodeGroup(Environment environment, String clusterName,
                                          String nodeGroupName, String region, AtomicInteger nextSequence) {
        EksSyncChanges changes = new EksSyncChanges();
        // Upsert VmGroup — always write identity metadata so the DB record is self-describing
        Optional<VmGroup> groupOpt = groupRepository
                .findByEnvironmentEnvironmentIdAndName(environment.getEnvironmentId(), nodeGroupName);
        boolean groupCreated = groupOpt.isEmpty();
        VmGroup group = groupOpt.orElseGet(() -> {
                    VmGroup g = new VmGroup();
                    g.setGroupId(UUID.randomUUID().toString());
                    g.setEnvironment(environment);
                    g.setName(nodeGroupName);
                    g.setDisplayName(nodeGroupName);
                    g.setSequencePosition(nextSequence.getAndIncrement());
                    log.info("Creating new VmGroup for EKS node group: {}/{}", clusterName, nodeGroupName);
                    return g;
                });
        group.setMetadata(buildVmGroupMetadata(clusterName, nodeGroupName, region));
        group = groupRepository.save(group);

        // Fetch live status and scaling config from EKS
        String providerVmId = clusterName + "/" + nodeGroupName;
        Nodegroup nodegroup = eksService.describeNodegroup(clusterName, nodeGroupName, region);
        VmStatus liveStatus = nodegroup != null ? eksService.mapNodegroupToVmStatus(nodegroup) : VmStatus.UNKNOWN;

        // Upsert Vm representing this node group
        final VmGroup savedGroup = group;
        Optional<Vm> vmOpt = vmRepository.findByGroupGroupIdAndName(savedGroup.getGroupId(), nodeGroupName);
        boolean vmCreated = vmOpt.isEmpty();
        Vm vm = vmOpt.orElseGet(() -> {
                    Vm v = new Vm();
                    v.setVmId(UUID.randomUUID().toString());
                    v.setGroup(savedGroup);
                    v.setName(nodeGroupName);
                    v.setDisplayName(nodeGroupName);
                    v.setProvider(CloudProvider.AWS_EKS);
                    v.setProviderVmId(providerVmId);
                    v.setRegion(region);
                    v.setSequencePosition(1);
                    v.setStatus(VmStatus.UNKNOWN);
                    v.setIsActive(true);
                    log.info("Creating new Vm for EKS node group: {}", providerVmId);
                    return v;
                });

        // Ensure Vm is active (may have been deactivated previously)
        vm.setIsActive(true);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId(providerVmId);
        vm.setRegion(region);

        // Scaling numbers (minSize/desiredSize) are only trustworthy while the group has at
        // least one of them > 0 — preserve the prior values when scaled to zero so startVm can
        // restore the correct count. instanceType is different: it's static node-group
        // configuration (from the same DescribeNodegroup call, no extra AWS API cost), not live
        // scaling state, so it's captured on every sync regardless of running state — otherwise
        // a node group that's currently stopped would never get priced by Cost Management.
        if (nodegroup != null && nodegroup.scalingConfig() != null) {
            int liveDesired = nodegroup.scalingConfig().desiredSize();
            int liveMin = nodegroup.scalingConfig().minSize();
            String instanceType = (nodegroup.instanceTypes() != null && !nodegroup.instanceTypes().isEmpty())
                    ? nodegroup.instanceTypes().get(0) : null;
            if (liveDesired > 0 || liveMin > 0) {
                vm.setMetadata(buildVmMetadata(liveMin, liveDesired, instanceType));
            } else if (instanceType != null) {
                vm.setMetadata(mergeInstanceTypeIntoMetadata(vm.getMetadata(), instanceType));
            }
        }

        // Drift detection
        VmStatus currentStatus = vm.getStatus();
        if (liveStatus != VmStatus.UNKNOWN && currentStatus != liveStatus) {
            log.info("EKS node group {}/{} drift detected: {} → {}", clusterName, nodeGroupName, currentStatus, liveStatus);
            vm.setStateDriftDetected(true);
            auditService.logAction(null, AuditAction.STATE_DRIFT_DETECTED, "vm", vm.getVmId(),
                    nodeGroupName, String.format("EKS node group drift: %s → %s", currentStatus, liveStatus));
            if (!groupCreated && !vmCreated) {
                changes.updated++;
            }
        } else {
            vm.setStateDriftDetected(false);
        }

        vm.setStatus(liveStatus != VmStatus.UNKNOWN ? liveStatus : currentStatus);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);
        if (groupCreated || vmCreated) {
            changes.created++;
        }
        return changes;
    }

    private String buildVmGroupMetadata(String clusterName, String nodeGroupName, String region) {
        try {
            return objectMapper.writeValueAsString(
                    java.util.Map.of("clusterName", clusterName, "nodeGroupName", nodeGroupName, "region", region));
        } catch (Exception e) {
            return "{\"clusterName\":\"" + clusterName + "\",\"nodeGroupName\":\"" + nodeGroupName + "\",\"region\":\"" + region + "\"}";
        }
    }

    private String buildVmMetadata(int minSize, int desiredSize, String instanceType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("minSize", minSize);
        fields.put("desiredSize", desiredSize);
        if (instanceType != null && !instanceType.isBlank()) {
            fields.put("instanceType", instanceType);
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            String instanceTypeJson = instanceType != null ? ",\"instanceType\":\"" + instanceType + "\"" : "";
            return "{\"minSize\":" + minSize + ",\"desiredSize\":" + desiredSize + instanceTypeJson + "}";
        }
    }

    /**
     * Refreshes just the instanceType field on an existing metadata JSON blob, preserving
     * whatever minSize/desiredSize is already there (used when the group is scaled to zero,
     * where those scaling numbers must NOT be overwritten but instanceType safely can be).
     */
    private String mergeInstanceTypeIntoMetadata(String existingMetadata, String instanceType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (existingMetadata != null && !existingMetadata.isBlank()) {
            try {
                Map<?, ?> existing = objectMapper.readValue(existingMetadata, Map.class);
                existing.forEach((key, value) -> fields.put(String.valueOf(key), value));
            } catch (Exception e) {
                log.debug("Could not parse existing Vm metadata while merging instanceType: {}", e.getMessage());
            }
        }
        fields.putIfAbsent("minSize", 0);
        fields.putIfAbsent("desiredSize", 0);
        fields.put("instanceType", instanceType);
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            return buildVmMetadata(
                    ((Number) fields.getOrDefault("minSize", 0)).intValue(),
                    ((Number) fields.getOrDefault("desiredSize", 0)).intValue(),
                    instanceType);
        }
    }

    /** Deactivates one removed node group's Vms in its own transaction (see {@link #self}). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deactivateNodeGroup(VmGroup group, String clusterName) {
        log.info("Deactivating EKS node group no longer in cluster {}: {}", clusterName, group.getName());
        // Deactivate all Vms in the group
        List<Vm> vms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(group.getGroupId());
        int removed = 0;
        for (Vm vm : vms) {
            if (Boolean.TRUE.equals(vm.getIsActive())) {
                vm.setIsActive(false);
                vm.setStatus(VmStatus.NOT_FOUND);
                vm.setStateDriftDetected(true);
                vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
                vmRepository.save(vm);
                auditService.logAction(null, AuditAction.STATE_DRIFT_DETECTED, "vm", vm.getVmId(),
                        vm.getName(), "EKS node group removed from cluster — marked inactive");
                removed++;
            }
        }
        return removed;
    }

    private void notifyEksChanges(Environment environment, EksSyncChanges changes) {
        if (changes.total() <= 0) {
            return;
        }
        if (!changes.failedNames.isEmpty()) {
            log.warn("EKS sync for '{}' had {} failed node group(s): {}",
                    environment.getName(), changes.failed, changes.failedNames);
        }
        try {
            notificationService.notifyEksSyncChanged(
                    environment.getEnvironmentId(),
                    environment.getName(),
                    changes.created,
                    changes.updated,
                    changes.removed,
                    changes.failed);
        } catch (Exception e) {
            log.warn("Could not notify EKS sync changes for environment {}: {}",
                    environment.getEnvironmentId(), e.getMessage());
        }
    }

    static class EksSyncChanges {
        private int created;
        private int updated;
        private int removed;
        private int failed;
        private final List<String> failedNames = new ArrayList<>();

        private EksSyncChanges() {
        }

        private EksSyncChanges(int created, int updated, int removed) {
            this.created = created;
            this.updated = updated;
            this.removed = removed;
        }

        private void add(EksSyncChanges other) {
            this.created += other.created;
            this.updated += other.updated;
            this.removed += other.removed;
            this.failed += other.failed;
            this.failedNames.addAll(other.failedNames);
        }

        private int total() {
            return created + updated + removed + failed;
        }
    }

    private String resolveRegion(Environment environment) {
        if (environment.getMetadata() != null && !environment.getMetadata().isBlank()) {
            try {
                Map<?, ?> meta = objectMapper.readValue(environment.getMetadata(), Map.class);
                Object region = meta.get("region");
                if (region instanceof String r && !r.isBlank()) {
                    return r;
                }
            } catch (Exception e) {
                log.warn("Failed to parse region from metadata for environment {}: {}", environment.getName(), e.getMessage());
            }
        }
        return defaultRegion;
    }
}
