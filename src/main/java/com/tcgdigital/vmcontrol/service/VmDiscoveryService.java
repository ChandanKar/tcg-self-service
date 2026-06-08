package com.tcgdigital.vmcontrol.service;

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

    @Value("${vm.discovery.strategy:tag}")
    private String discoveryStrategy;

    @Value("${vm.discovery.tag.environment-key:tcg:environment}")
    private String envTagKey;

    @Value("${vm.discovery.name-pattern.prefix:}")
    private String namePrefix;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    public VmDiscoveryService(EnvironmentRepository environmentRepository,
                              VmGroupRepository vmGroupRepository,
                              VmRepository vmRepository,
                              AwsCloudProviderService awsService,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.environmentRepository = environmentRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.vmRepository = vmRepository;
        this.awsService = awsService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
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
        log.info("VM discovery (name-pattern) starting — prefix='{}', region={}",
                namePrefix.isBlank() ? "(all)" : namePrefix, defaultRegion);

        List<Instance> allInstances = awsService.discoverInstancesByNamePrefix(defaultRegion, namePrefix);
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
            e.setDescription("Auto-discovered via naming pattern");
            e.setServiceType("EC2");
            e.setIsActive(true);
            e.setMetadata("{\"region\":\"" + region + "\"}");
            environmentRepository.save(e);
            log.info("Auto-created EC2 environment '{}' from naming pattern", envName);
            auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "environment",
                    e.getEnvironmentId(), envName,
                    "Environment auto-created by name-pattern discovery in region " + region);
            return e;
        });

        VmGroup group = findOrCreateDiscoveryGroup(env);

        int registered = 0;
        for (Instance instance : instances) {
            if (vmRepository.existsByProviderAndProviderVmId(CloudProvider.AWS, instance.instanceId())) {
                continue;
            }
            registerInstanceByName(instance, env, group, region);
            registered++;
        }

        // Flag VMs in this group that are no longer present in AWS
        Set<String> liveIds = instances.stream().map(Instance::instanceId).collect(Collectors.toSet());
        flagMissingVms(group, liveIds);

        return registered;
    }

    private void registerInstanceByName(Instance instance, Environment env, VmGroup group, String region) {
        String nameTag = getNameTag(instance);
        String displayName = nameTag != null ? nameTag : instance.instanceId();
        String slug = toSlug(displayName);

        if (vmRepository.existsByGroupGroupIdAndName(group.getGroupId(), slug)) {
            slug = slug + "-" + instance.instanceId().substring(Math.max(0, instance.instanceId().length() - 4));
        }

        int seqPos = extractSeqNumber(nameTag);
        if (seqPos <= 0 || vmRepository.existsByGroupGroupIdAndSequencePosition(group.getGroupId(), seqPos)) {
            seqPos = (int) vmRepository.countByGroupGroupId(group.getGroupId()) + 1;
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
        for (Environment env : environments) {
            try {
                total += discoverEnvironmentVms(env);
            } catch (Exception e) {
                log.error("Discovery failed for environment {}: {}", env.getName(), e.getMessage(), e);
            }
        }
        log.info("VM discovery (tag) complete — {} new VM(s) registered", total);
        return total;
    }

    private int discoverEnvironmentVms(Environment env) {
        String region = resolveRegion(env);
        if (region == null) {
            log.warn("Skipping discovery for environment {} — no region in metadata. " +
                     "Set environment.metadata to {{\"region\":\"ap-south-1\"}} to enable.", env.getName());
            return 0;
        }
        List<Instance> liveInstances = awsService.discoverTaggedInstances(region, envTagKey, env.getName());
        log.info("Environment {}: {} tagged instance(s) in region {}", env.getName(), liveInstances.size(), region);

        Set<String> liveInstanceIds = liveInstances.stream().map(Instance::instanceId).collect(Collectors.toSet());
        VmGroup discoveryGroup = findOrCreateDiscoveryGroup(env);

        int registered = 0;
        for (Instance instance : liveInstances) {
            if (vmRepository.existsByProviderAndProviderVmId(CloudProvider.AWS, instance.instanceId())) {
                continue;
            }
            registerInstanceByTag(instance, env, discoveryGroup, region);
            registered++;
        }
        flagMissingVms(discoveryGroup, liveInstanceIds);
        return registered;
    }

    private void registerInstanceByTag(Instance instance, Environment env, VmGroup group, String region) {
        String nameTag = getNameTag(instance);
        String displayName = nameTag != null ? nameTag : instance.instanceId();
        String slug = toSlug(displayName);

        if (vmRepository.existsByGroupGroupIdAndName(group.getGroupId(), slug)) {
            slug = slug + "-" + instance.instanceId().substring(Math.max(0, instance.instanceId().length() - 4));
        }

        int seqPos = (int) vmRepository.countByGroupGroupId(group.getGroupId()) + 1;
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

    private void flagMissingVms(VmGroup group, Set<String> liveInstanceIds) {
        List<Vm> groupVms = vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(group.getGroupId());
        for (Vm vm : groupVms) {
            if (Boolean.TRUE.equals(vm.getIsActive()) && !liveInstanceIds.contains(vm.getProviderVmId())) {
                vm.setStateDriftDetected(true);
                vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
                vmRepository.save(vm);
                log.warn("VM '{}' (instance={}) no longer found in AWS — drift flagged", vm.getName(), vm.getProviderVmId());
            }
        }
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

    private String getNameTag(Instance instance) {
        return instance.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .findFirst()
                .orElse(null);
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
