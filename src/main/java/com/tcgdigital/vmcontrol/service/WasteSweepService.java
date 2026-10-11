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

    @Value("${waste.sweep.enabled:false}")
    private boolean enabled = false;

    @Value("${waste.sweep.regions:}")
    private String regions = "";

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion = "ap-south-1";

    @Value("${waste.sweep.unattached-days:32}")
    private int unattachedDays = 32;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix = "tcg:";

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public WasteSweepService(WasteFindingRepository findingRepository, AwsWasteScanService scanService, WastePricing pricing,
                             EnvironmentRepository environmentRepository, ObjectMapper objectMapper) {
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
        return scans;
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
