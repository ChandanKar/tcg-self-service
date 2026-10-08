package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.DiscoveryFailedException;
import com.tcgdigital.vmcontrol.service.support.NameSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VmDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(VmDiscoveryService.class);
    private static final String DISCOVERY_GROUP_NAME = "discovered";
    private static final String DISCOVERY_GROUP_DISPLAY = "Auto-Discovered";
    private static final int DISCOVERY_GROUP_SEQ = 999;
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.+?)-(\\d+)$");

    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository vmGroupRepository;
    private final VmRepository vmRepository;
    private final AwsCloudProviderService awsService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TagReconciliationService tagReconciliationService;

    @Value("${vm.discovery.strategy:tag}")
    private String discoveryStrategy;

    @Value("${vm.discovery.tag.environment-key:tcg:environment}")
    private String envTagKey;

    @Value("${vm.discovery.name-pattern.prefix:}")
    private String namePrefix;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    /** New name-pattern environments start inactive until an admin reactivates them (default). */
    @Value("${vm.discovery.name-pattern.auto-activate-environments:false}")
    private boolean autoActivateEnvironments;

    public VmDiscoveryService(EnvironmentRepository environmentRepository,
                              VmGroupRepository vmGroupRepository,
                              VmRepository vmRepository,
                              AwsCloudProviderService awsService,
                              AuditService auditService,
                              ObjectMapper objectMapper,
                              TagReconciliationService tagReconciliationService) {
        this.environmentRepository = environmentRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.vmRepository = vmRepository;
        this.awsService = awsService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.tagReconciliationService = tagReconciliationService;
    }

    /**
     * Reconciles cost-allocation tags for an environment right after discovering VMs for it, so
     * newly-registered VMs don't have to wait for the nightly sweep. A tagging failure must
     * never block discovery — it's a best-effort side effect, not a required step.
     */
    private void reconcileTagsSafely(Environment env) {
        try {
            tagReconciliationService.reconcileEnvironment(env);
        } catch (Exception e) {
            log.warn("Tag reconciliation failed for environment {} (discovery unaffected): {}", env.getName(), e.getMessage());
        }
    }

    public int discoverAndRegisterVms() {
        if (!awsService.isAvailable()) {
            log.warn("AWS not available - skipping VM discovery");
            return 0;
        }
        if ("name-pattern".equalsIgnoreCase(discoveryStrategy)) {
            return discoverByNamePattern();
        }
        return discoverByTag();
    }

    // -------------------------------------------------------------------------
    // Strategy: name-pattern
    // Scans instances whose Name tag starts with namePrefix, strips trailing
    // "-<number>" to derive the environment name, groups and registers them.
    // e.g. tcg-backend-1, tcg-backend-2, tcg-backend-3 → env "tcg-backend"
    // -------------------------------------------------------------------------

    private int discoverByNamePattern() {
        // An empty prefix scanned (and grouped into environments) every instance in the account.
        if (namePrefix == null || namePrefix.isBlank()) {
            log.warn("name-pattern discovery requires vm.discovery.name-pattern.prefix; skipping");
            return 0;
        }
        log.info("VM discovery (name-pattern) starting — prefix='{}', region={}",
                namePrefix.isBlank() ? "(all)" : namePrefix, defaultRegion);

        List<Instance> allInstances;
        try {
            allInstances = awsService.discoverInstancesByNamePrefix(defaultRegion, namePrefix);
        } catch (DiscoveryFailedException e) {
            // Unknown is not "nothing is live": register and flag nothing this run (H24).
            log.warn("VM discovery (name-pattern) skipped — {}", e.getMessage());
            return 0;
        }
        if (allInstances.isEmpty()) {
            log.info("No instances found matching prefix '{}' in region {}", namePrefix, defaultRegion);
            return 0;
        }

        // Group instances by extracted environment name
        Map<String, List<Instance>> byEnv = new LinkedHashMap<>();
        Set<String> skipped = new LinkedHashSet<>();
        for (Instance instance : allInstances) {
            String nameTag = getNameTag(instance);
            String envName = extractEnvName(nameTag);
            if (envName == null) {
                skipped.add(nameTag != null ? nameTag : instance.instanceId());
                continue;
            }
            byEnv.computeIfAbsent(envName, k -> new ArrayList<>()).add(instance);
        }

        if (!skipped.isEmpty()) {
            log.debug("Skipped {} instance(s) with no trailing number in Name tag: {}", skipped.size(), skipped);
        }

        log.info("name-pattern discovery: {} instance(s) grouped into {} environment(s): {}",
                allInstances.size() - skipped.size(), byEnv.size(), byEnv.keySet());

        int total = 0;
        for (Map.Entry<String, List<Instance>> entry : byEnv.entrySet()) {
            try {
                total += registerInstanceGroup(entry.getKey(), entry.getValue(), defaultRegion);
            } catch (Exception e) {
                log.error("Failed to register instance group '{}': {}", entry.getKey(), e.getMessage(), e);
            }
        }

        log.info("VM discovery (name-pattern) complete — {} new VM(s) registered", total);
        return total;
    }

    private int registerInstanceGroup(String envName, List<Instance> instances, String region) {
        // Find or create Environment
        Environment env = environmentRepository.findByName(envName).orElseGet(() -> {
            Environment e = new Environment();
            e.setEnvironmentId(UUID.randomUUID().toString());
            e.setName(envName);
            e.setDisplayName(envName);
            e.setDescription("Auto-discovered via naming pattern - pending admin review");
            e.setServiceType("EC2");
            e.setIsActive(autoActivateEnvironments);
            e.setMetadata("{\"region\":\"" + region + "\"}");
            environmentRepository.save(e);
            log.info("Auto-created EC2 environment '{}' from naming pattern (active={})", envName, autoActivateEnvironments);
            auditService.logAction(null, AuditAction.ENVIRONMENT_CREATED, "environment",
                    e.getEnvironmentId(), envName,
                    "Environment auto-created by name-pattern discovery in region " + region
                            + (autoActivateEnvironments ? "" : "; inactive until an admin reactivates it"));
            return e;
        });
        // Nothing is registered into an inactive environment, including one just created for review.
        if (!Boolean.TRUE.equals(env.getIsActive())) {
            log.info("name-pattern discovery: environment '{}' is inactive; {} instance(s) not registered",
                    envName, instances.size());
            return 0;
        }

        VmGroup group = findOrCreateDiscoveryGroup(env);

        int registered = 0;
        int failed = 0;
        for (Instance instance : instances) {
            if (vmRepository.existsByProviderAndProviderVmId(CloudProvider.AWS, instance.instanceId())) {
                continue;
            }
            // One bad instance must not stop the rest of the environment (H8).
            try {
                registerInstanceByName(instance, env, group, region);
                registered++;
            } catch (Exception e) {
                failed++;
                log.error("Could not register instance {} in environment {}: {}",
                        instance.instanceId(), env.getName(), e.getMessage(), e);
            }
        }
        if (failed > 0) {
            log.warn("Environment {}: {} instance(s) could not be registered", env.getName(), failed);
        }

        // Flag VMs in this group that are no longer present in AWS
        Set<String> liveIds = instances.stream().map(Instance::instanceId).collect(Collectors.toSet());
        flagMissingVms(group, liveIds);

        reconcileTagsSafely(env);
        return registered;
    }

    private void registerInstanceByName(Instance instance, Environment env, VmGroup group, String region) {
        String nameTag = getNameTag(instance);
        String displayName = nameTag != null ? nameTag : instance.instanceId();
        String slug = toSlug(displayName);

        if (vmRepository.existsByGroupGroupIdAndName(group.getGroupId(), slug)) {
            slug = slug + "-" + instance.instanceId().substring(Math.max(0, instance.instanceId().length() - 4));
        }

        // Keep the Name tag's number when it is free (the check covers inactive rows too).
        int seqPos = extractSeqNumber(nameTag);
        if (seqPos <= 0 || vmRepository.existsByGroupGroupIdAndSequencePosition(group.getGroupId(), seqPos)) {
            seqPos = nextSequencePosition(group.getGroupId());
        }

        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(slug);
        vm.setDisplayName(displayName);
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId(instance.instanceId());
        vm.setRegion(region);
        vm.setStatus(VmStatus.UNKNOWN);
        vm.setSequencePosition(seqPos);
        vm.setIsActive(true);
        vm.setDiscoveryPending(true);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);

        auditService.logAction(null, AuditAction.VM_DISCOVERED_UNTRACKED, "vm", vm.getVmId(),
                displayName, String.format("Auto-discovered via name-pattern (env=%s) — pending admin review", env.getName()));
        log.info("Registered VM '{}' (instance={}) in environment '{}'", displayName, instance.instanceId(), env.getName());
    }

    // -------------------------------------------------------------------------
    // Strategy: tag (original behaviour)
    // Reads existing EC2 environments from DB, scans for instances tagged with
    // envTagKey=<env-name>, registers untracked instances.
    // -------------------------------------------------------------------------

    private int discoverByTag() {
        List<Environment> environments = environmentRepository.findActiveEc2Environments();
        log.info("VM discovery (tag) starting for {} active EC2 environment(s)", environments.size());
        int total = 0;
        int failedEnvironments = 0;
        int fallbackEnvironments = 0;
        for (Environment env : environments) {
            try {
                Regions regions = resolveRegions(env);
                if (regions.fallback()) {
                    fallbackEnvironments++;
                }
                total += discoverEnvironmentVms(env, regions.regions());
            } catch (DiscoveryFailedException e) {
                failedEnvironments++;
                log.warn("Discovery skipped for environment {} — {}", env.getName(), e.getMessage());
            } catch (Exception e) {
                failedEnvironments++;
                log.error("Discovery failed for environment {}: {}", env.getName(), e.getMessage(), e);
            }
        }
        log.info("VM discovery (tag) complete — {} new VM(s) registered, {} environment(s) failed, " +
                 "{} environment(s) without a metadata region used a fallback region", total, failedEnvironments,
                fallbackEnvironments);
        return total;
    }

    private int discoverEnvironmentVms(Environment env, List<String> regions) {
        // instance -> the region it was found in; a failed region is unknown, not empty (H24).
        Map<Instance, String> liveInstances = new LinkedHashMap<>();
        int failedRegions = 0;
        DiscoveryFailedException lastFailure = null;
        for (String region : regions) {
            try {
                List<Instance> found = awsService.discoverTaggedInstances(region, envTagKey, env.getName());
                log.info("Environment {}: {} tagged instance(s) in region {}", env.getName(), found.size(), region);
                found.forEach(i -> liveInstances.put(i, region));
            } catch (DiscoveryFailedException e) {
                failedRegions++;
                lastFailure = e;
            }
        }
        if (failedRegions == regions.size()) {
            throw lastFailure; // nothing is registered, flagged or re-tagged
        }

        Set<String> liveInstanceIds = liveInstances.keySet().stream().map(Instance::instanceId).collect(Collectors.toSet());
        // Created on the first registration only: legacy environments without discovered VMs
        // should not each gain an empty Auto-Discovered group.
        VmGroup discoveryGroup = findDiscoveryGroup(env).orElse(null);

        int registered = 0;
        int failed = 0;
        for (Map.Entry<Instance, String> entry : liveInstances.entrySet()) {
            Instance instance = entry.getKey();
            if (vmRepository.existsByProviderAndProviderVmId(CloudProvider.AWS, instance.instanceId())) {
                continue;
            }
            // One bad instance must not stop the rest of the environment (H8).
            try {
                if (discoveryGroup == null) {
                    discoveryGroup = findOrCreateDiscoveryGroup(env);
                }
                registerInstanceByTag(instance, env, discoveryGroup, entry.getValue());
                registered++;
            } catch (Exception e) {
                failed++;
                log.error("Could not register instance {} in environment {}: {}",
                        instance.instanceId(), env.getName(), e.getMessage(), e);
            }
        }
        if (failed > 0) {
            log.warn("Environment {}: {} instance(s) could not be registered", env.getName(), failed);
        }
        if (failedRegions > 0) {
            // A VM in the failed region would look missing: flag nothing and re-tag nothing this run.
            log.warn("Environment {}: {} of {} region(s) failed; missing-VM flagging skipped",
                    env.getName(), failedRegions, regions.size());
            return registered;
        }
        if (discoveryGroup != null) {
            flagMissingVms(discoveryGroup, liveInstanceIds);
        }
        reconcileTagsSafely(env);
        return registered;
    }

    private void registerInstanceByTag(Instance instance, Environment env, VmGroup group, String region) {
        String nameTag = getNameTag(instance);
        String displayName = nameTag != null ? nameTag : instance.instanceId();
        String slug = toSlug(displayName);

        if (vmRepository.existsByGroupGroupIdAndName(group.getGroupId(), slug)) {
            slug = slug + "-" + instance.instanceId().substring(Math.max(0, instance.instanceId().length() - 4));
        }

        int seqPos = nextSequencePosition(group.getGroupId());
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(slug);
        vm.setDisplayName(displayName);
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId(instance.instanceId());
        vm.setRegion(region);
        vm.setStatus(VmStatus.UNKNOWN);
        vm.setSequencePosition(seqPos);
        vm.setIsActive(true);
        vm.setDiscoveryPending(true);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);

        auditService.logAction(null, AuditAction.VM_DISCOVERED_UNTRACKED, "vm", vm.getVmId(),
                displayName, String.format("Auto-discovered via tag %s=%s — pending admin review", envTagKey, env.getName()));
        log.info("Registered VM '{}' (instance={}, env={})", displayName, instance.instanceId(), env.getName());
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Next free position in a group: one above the highest over all rows. Counting active VMs
     * collided with an inactive VM's position forever once one VM was deactivated (H8).
     */
    private int nextSequencePosition(String groupId) {
        Integer max = vmRepository.findMaxSequencePositionByGroupId(groupId);
        return (max == null ? 0 : max) + 1;
    }

    private void flagMissingVms(VmGroup group, Set<String> liveInstanceIds) {
        List<Vm> groupVms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(group.getGroupId());
        for (Vm vm : groupVms) {
            if (Boolean.TRUE.equals(vm.getIsActive()) && !liveInstanceIds.contains(vm.getProviderVmId())) {
                // Narrow update: only the drift flag, never a stale copy of the whole row (M5).
                if (vmRepository.markDriftIfActive(vm.getVmId(), Timestamp.from(Instant.now())) > 0) {
                    log.warn("VM '{}' (instance={}) no longer found in AWS — drift flagged", vm.getName(), vm.getProviderVmId());
                }
            }
        }
    }

    private Optional<VmGroup> findDiscoveryGroup(Environment env) {
        return vmGroupRepository.findByEnvironmentEnvironmentIdAndName(env.getEnvironmentId(), DISCOVERY_GROUP_NAME);
    }

    private VmGroup findOrCreateDiscoveryGroup(Environment env) {
        return vmGroupRepository
                .findByEnvironmentEnvironmentIdAndName(env.getEnvironmentId(), DISCOVERY_GROUP_NAME)
                .orElseGet(() -> {
                    VmGroup g = new VmGroup();
                    g.setGroupId(UUID.randomUUID().toString());
                    g.setEnvironment(env);
                    g.setName(DISCOVERY_GROUP_NAME);
                    g.setDisplayName(DISCOVERY_GROUP_DISPLAY);
                    g.setSequencePosition(DISCOVERY_GROUP_SEQ);
                    log.info("Created '{}' group for environment '{}'", DISCOVERY_GROUP_DISPLAY, env.getName());
                    return vmGroupRepository.save(g);
                });
    }

    private String extractEnvName(String nameTag) {
        if (nameTag == null || nameTag.isBlank()) return null;
        Matcher m = TRAILING_NUMBER.matcher(nameTag);
        return m.matches() ? m.group(1).toLowerCase() : null;
    }

    private int extractSeqNumber(String nameTag) {
        if (nameTag == null) return 0;
        Matcher m = TRAILING_NUMBER.matcher(nameTag);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /** The EC2 Name tag with markup characters removed (NameSanitizer), or null if absent/empty. */
    private String getNameTag(Instance instance) {
        return instance.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .map(NameSanitizer::clean)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Regions to scan, and whether they came from a fallback rather than the metadata. */
    record Regions(List<String> regions, boolean fallback) {}

    /**
     * Metadata region if set; else the distinct regions of the environment's active VMs; else
     * the default region. Legacy environments have no metadata region and were never scanned.
     */
    Regions resolveRegions(Environment env) {
        String metadataRegion = resolveRegion(env);
        if (metadataRegion != null) {
            return new Regions(List.of(metadataRegion), false);
        }
        List<String> vmRegions = vmRepository.findDistinctRegionsGroupedByEnvironment(List.of(env.getEnvironmentId()))
                .stream().map(VmRepository.EnvironmentRegion::getRegion).distinct().sorted().toList();
        if (!vmRegions.isEmpty()) {
            return new Regions(vmRegions, true);
        }
        return new Regions(List.of(defaultRegion), true);
    }

    private String resolveRegion(Environment env) {
        if (env.getMetadata() != null && !env.getMetadata().isBlank()) {
            try {
                Map<?, ?> meta = objectMapper.readValue(env.getMetadata(), Map.class);
                Object region = meta.get("region");
                if (region instanceof String r && !r.isBlank()) return r;
            } catch (Exception e) {
                log.warn("Failed to parse metadata for environment {}: {}", env.getName(), e.getMessage());
            }
        }
        return null;
    }

    private String toSlug(String input) {
        return input.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
