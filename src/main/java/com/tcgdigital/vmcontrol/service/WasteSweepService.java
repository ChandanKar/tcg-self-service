package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The daily waste sweep (E22). Off by default (waste.sweep.enabled). For each region and each
 * scan, in its own try/catch: findings are upserted (a new one OPEN, a seen one refreshed, a
 * DISMISSED or ACTION_PENDING one left as it is), and only after that scan succeeded are the OPEN
 * findings it no longer saw marked RESOLVED. A failed scan leaves its region's findings unchanged.
 */
@Service
public class WasteSweepService {

    private static final Logger log = LoggerFactory.getLogger(WasteSweepService.class);

    /** A resource a scan reports as waste, priced and linked. */
    public record Candidate(String resourceId, String name, BigDecimal monthlyCost, Instant wasteSince,
                            String environmentId, String vmId, Map<String, Object> details) {}

    /** Per type: findings open after the sweep and failed region scans. */
    public record SweepResult(boolean ran, Map<WasteFindingType, Integer> found, List<String> failures) {}

    private final WasteFindingRepository findingRepository;
    private final AwsWasteScanService scanService;
    private final WastePricing pricing;
    private final EnvironmentRepository environmentRepository;
    private final ObjectMapper objectMapper;
    private final com.tcgdigital.vmcontrol.repository.VmRepository vmRepository;
    private final com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository historyRepository;
    private final AuditService auditService;

    @Value("${waste.sweep.enabled:false}")
    private boolean enabled = false;

    @Value("${waste.sweep.regions:}")
    private String regions = "";

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion = "ap-south-1";

    @Value("${waste.sweep.unattached-days:32}")
    private int unattachedDays = 32;

    @Value("${waste.sweep.stopped-days:30}")
    private int stoppedDays = 30;

    @Value("${waste.sweep.snapshot-age-days:90}")
    private int snapshotAgeDays = 90;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix = "tcg:";

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public WasteSweepService(WasteFindingRepository findingRepository, AwsWasteScanService scanService, WastePricing pricing,
                             EnvironmentRepository environmentRepository, ObjectMapper objectMapper,
                             com.tcgdigital.vmcontrol.repository.VmRepository vmRepository,
                             com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository historyRepository,
                             AuditService auditService) {
        this.vmRepository = vmRepository;
        this.historyRepository = historyRepository;
        this.auditService = auditService;
        this.findingRepository = findingRepository;
        this.scanService = scanService;
        this.pricing = pricing;
        this.environmentRepository = environmentRepository;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SweepResult sweep() {
        if (!enabled) {
            return new SweepResult(false, Map.of(), List.of());
        }
        Instant started = clock.instant();
        Map<String, String> environmentIdsByName = environmentRepository.findAll().stream()
                .collect(Collectors.toMap(Environment::getName, Environment::getEnvironmentId, (a, b) -> a));
        Map<WasteFindingType, Integer> found = new EnumMap<>(WasteFindingType.class);
        List<String> failures = new java.util.ArrayList<>();
        for (String region : regions()) {
            for (Map.Entry<WasteFindingType, Function<String, List<Candidate>>> scan : scans(environmentIdsByName, started).entrySet()) {
                WasteFindingType type = scan.getKey();
                try {
                    List<Candidate> candidates = scan.getValue().apply(region);
                    candidates.forEach(c -> upsert(type, region, c, started));
                    resolveUnseen(type, region, started);
                    found.merge(type, candidates.size(), Integer::sum);
                } catch (Exception e) {
                    log.warn("Waste scan {} in {} failed; its findings are left as they were: {}", type, region, e.getMessage());
                    failures.add(type + " in " + region + ": " + e.getMessage());
                }
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (WasteFinding f : findingRepository.findAll()) {
            if (f.getStatus() == WasteFindingStatus.OPEN && f.getEstMonthlyCost() != null) {
                total = total.add(f.getEstMonthlyCost());
            }
        }
        try {
            auditService.logAction(null, com.tcgdigital.vmcontrol.model.AuditAction.SCHEDULED_JOB_EXECUTED, "job", "waste_sweep",
                    "Waste sweep", "Found " + found + "; open waste about $" + total + "/month (estimated)"
                            + (failures.isEmpty() ? "" : "; failed: " + failures));
        } catch (Exception e) {
            log.warn("Could not audit the waste sweep: {}", e.getMessage());
        }
        return new SweepResult(true, found, failures);
    }

    List<String> regions() {
        List<String> list = Arrays.stream(regions == null ? new String[0] : regions.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        return list.isEmpty() ? List.of(defaultRegion) : list;
    }

    /** The scans the sweep runs, in order. */
    Map<WasteFindingType, Function<String, List<Candidate>>> scans(Map<String, String> environmentIdsByName, Instant now) {
        Map<WasteFindingType, Function<String, List<Candidate>>> scans = new LinkedHashMap<>();
        scans.put(WasteFindingType.UNATTACHED_VOLUME, region -> unattachedVolumes(region, environmentIdsByName, now));
        scans.put(WasteFindingType.UNASSOCIATED_IP, region -> unassociatedIps(region, environmentIdsByName, now));
        scans.put(WasteFindingType.GP2_VOLUME, region -> gp2Volumes(region, environmentIdsByName));
        scans.put(WasteFindingType.LONG_STOPPED_VM, region -> longStoppedVms(region, environmentIdsByName, now));
        scans.put(WasteFindingType.OLD_SNAPSHOT, region -> oldSnapshots(region, environmentIdsByName, now));
        return scans;
    }

    private String environmentOf(Map<String, String> tags, Map<String, String> environmentIdsByName) {
        return environmentIdsByName.get(tags.get(tagKeyPrefix + "environment"));
    }

    /** First seen (or now): an address has no creation time. */
    private Instant seenSince(WasteFindingType type, String region, String resourceId, Instant now) {
        return findingRepository.findByFindingTypeAndRegionAndResourceId(type, region, resourceId)
                .map(f -> f.getEligibleSince() != null ? f.getEligibleSince().toInstant() : f.getFirstSeenAt().toInstant())
                .orElse(now);
    }

    List<Candidate> unassociatedIps(String region, Map<String, String> environmentIdsByName, Instant now) {
        return scanService.scanUnassociatedAddresses(region).stream().map(a -> new Candidate(a.resourceId(), a.name(),
                pricing.publicIpv4Monthly(), seenSince(WasteFindingType.UNASSOCIATED_IP, region, a.resourceId(), now),
                environmentOf(a.tags(), environmentIdsByName), null, a.facts())).toList();
    }

    /** The saving of moving to gp3 is the finding's cost; the current cost is in the details. */
    List<Candidate> gp2Volumes(String region, Map<String, String> environmentIdsByName) {
        BigDecimal perGb = pricing.ebsRate("gp2").subtract(pricing.ebsRate("gp3"));
        return scanService.scanGp2Volumes(region).stream().map(v -> {
            Map<String, Object> details = new LinkedHashMap<>(v.facts());
            details.put("sizeGib", v.sizeGib());
            details.put("currentMonthlyCost", pricing.ebsMonthly("gp2", v.sizeGib()));
            return new Candidate(v.resourceId(), v.name(),
                    perGb.multiply(BigDecimal.valueOf(v.sizeGib())).setScale(2, java.math.RoundingMode.HALF_UP),
                    v.createdAt(), environmentOf(v.tags(), environmentIdsByName), null, details);
        }).toList();
    }

    /**
     * Instances stopped at least stopped-days: since their registered VM's last STOP transition,
     * else since the time in StateTransitionReason. Cost: their volumes, plus an Elastic IP.
     */
    @SuppressWarnings("unchecked")
    List<Candidate> longStoppedVms(String region, Map<String, String> environmentIdsByName, Instant now) {
        List<AwsWasteScanService.ScannedResource> instances = scanService.scanStoppedInstances(region);
        if (instances.isEmpty()) {
            return List.of();
        }
        Map<String, com.tcgdigital.vmcontrol.model.Vm> registered = new LinkedHashMap<>();
        vmRepository.findByProviderVmIdsFetchEnvironment(com.tcgdigital.vmcontrol.model.CloudProvider.AWS,
                instances.stream().map(AwsWasteScanService.ScannedResource::resourceId).toList())
                .forEach(vm -> registered.put(vm.getProviderVmId(), vm));
        Map<String, Instant> lastStopped = new java.util.HashMap<>();
        if (!registered.isEmpty()) {
            historyRepository.findLastStoppedAt(registered.values().stream().map(com.tcgdigital.vmcontrol.model.Vm::getVmId).toList())
                    .forEach(row -> lastStopped.put((String) row[0], ((Timestamp) row[1]).toInstant()));
        }
        Instant threshold = now.minus(Duration.ofDays(stoppedDays));
        List<Candidate> out = new java.util.ArrayList<>();
        List<AwsWasteScanService.ScannedResource> old = new java.util.ArrayList<>();
        Map<String, Instant> since = new LinkedHashMap<>();
        for (AwsWasteScanService.ScannedResource i : instances) {
            com.tcgdigital.vmcontrol.model.Vm vm = registered.get(i.resourceId());
            Instant stopped = vm != null && lastStopped.containsKey(vm.getVmId()) ? lastStopped.get(vm.getVmId())
                    : AwsWasteScanService.stoppedAt((String) i.facts().get("stateTransitionReason"));
            if (stopped != null && !stopped.isAfter(threshold)) {
                old.add(i);
                since.put(i.resourceId(), stopped);
            }
        }
        List<String> volumeIds = old.stream().flatMap(i -> ((List<String>) i.facts().getOrDefault("volumeIds", List.of())).stream()).toList();
        Map<String, AwsWasteScanService.ScannedResource> volumes = scanService.volumesById(region, volumeIds);
        for (AwsWasteScanService.ScannedResource i : old) {
            BigDecimal cost = BigDecimal.ZERO;
            int gib = 0;
            for (String id : (List<String>) i.facts().getOrDefault("volumeIds", List.of())) {
                AwsWasteScanService.ScannedResource v = volumes.get(id);
                if (v != null) {
                    cost = cost.add(pricing.ebsMonthly(v.volumeType(), v.sizeGib()));
                    gib += v.sizeGib();
                }
            }
            if (i.facts().get("publicIp") != null) {
                cost = cost.add(pricing.publicIpv4Monthly());
            }
            com.tcgdigital.vmcontrol.model.Vm vm = registered.get(i.resourceId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("instanceType", i.facts().get("instanceType"));
            details.put("volumeGib", gib);
            details.put("stoppedSince", since.get(i.resourceId()).toString());
            out.add(new Candidate(i.resourceId(), vm != null ? vm.getName() : i.name(), cost, since.get(i.resourceId()),
                    vm != null ? vm.getGroup().getEnvironment().getEnvironmentId() : environmentOf(i.tags(), environmentIdsByName),
                    vm != null ? vm.getVmId() : null, details));
        }
        return out;
    }

    /** Own snapshots older than snapshot-age-days that no own AMI uses; cost is an upper bound. */
    List<Candidate> oldSnapshots(String region, Map<String, String> environmentIdsByName, Instant now) {
        return scanService.scanOldSnapshots(region, now.minus(Duration.ofDays(snapshotAgeDays))).stream().map(s -> {
            Map<String, Object> details = new LinkedHashMap<>(s.facts());
            details.put("sizeGib", s.sizeGib());
            details.put("costIsUpperBound", true);
            return new Candidate(s.resourceId(), s.name(), pricing.snapshotMonthly(s.sizeGib()), s.createdAt(),
                    environmentOf(s.tags(), environmentIdsByName), null, details);
        }).toList();
    }

    /** Volumes unattached for at least unattached-days (created at least that long ago, or first seen then). */
    List<Candidate> unattachedVolumes(String region, Map<String, String> environmentIdsByName, Instant now) {
        Instant threshold = now.minus(Duration.ofDays(unattachedDays));
        return scanService.scanUnattachedVolumes(region).stream().map(v -> {
            Instant since = findingRepository.findByFindingTypeAndRegionAndResourceId(WasteFindingType.UNATTACHED_VOLUME, region, v.resourceId())
                    .map(f -> f.getEligibleSince() != null ? f.getEligibleSince().toInstant() : f.getFirstSeenAt().toInstant())
                    .orElse(v.createdAt() != null ? v.createdAt() : now);
            if (since.isAfter(threshold)) {
                return null; // not unattached long enough yet
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("volumeType", v.volumeType());
            details.put("sizeGib", v.sizeGib());
            return new Candidate(v.resourceId(), v.name(), pricing.ebsMonthly(v.volumeType(), v.sizeGib()), since,
                    environmentIdsByName.get(v.tags().get(tagKeyPrefix + "environment")), null, details);
        }).filter(java.util.Objects::nonNull).toList();
    }

    private void upsert(WasteFindingType type, String region, Candidate c, Instant now) {
        Timestamp seen = Timestamp.from(now);
        WasteFinding f = findingRepository.findByFindingTypeAndRegionAndResourceId(type, region, c.resourceId()).orElse(null);
        if (f == null) {
            f = new WasteFinding();
            f.setFindingId(UUID.randomUUID().toString());
            f.setFindingType(type);
            f.setRegion(region);
            f.setResourceId(c.resourceId());
            f.setFirstSeenAt(seen);
            f.setStatus(WasteFindingStatus.OPEN);
        } else if (f.getStatus() == WasteFindingStatus.RESOLVED) {
            f.setStatus(WasteFindingStatus.OPEN); // it came back
        }
        f.setLastSeenAt(seen);
        f.setEligibleSince(c.wasteSince() == null ? null : Timestamp.from(c.wasteSince()));
        f.setResourceName(c.name());
        f.setEnvironmentId(c.environmentId());
        f.setVmId(c.vmId());
        f.setEstMonthlyCost(c.monthlyCost());
        f.setDetails(json(c.details()));
        findingRepository.save(f);
    }

    private void resolveUnseen(WasteFindingType type, String region, Instant started) {
        List<WasteFinding> gone = findingRepository.findNotSeenSince(type, region, WasteFindingStatus.OPEN, Timestamp.from(started));
        gone.forEach(f -> f.setStatus(WasteFindingStatus.RESOLVED));
        findingRepository.saveAll(gone);
    }

    private String json(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            return null;
        }
    }
}
