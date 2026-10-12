package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerOption;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerRecommendation;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.ComputeOptimizerRecommendationRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.ComputeOptimizerException;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsResponse;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendationOption;
import software.amazon.awssdk.services.computeoptimizer.model.OptInRequiredException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Overlays AWS Compute Optimizer's real, ML-driven EC2 rightsizing recommendations onto the
 * home-grown rules in {@link CostEstimationService} — a second signal, never a replacement:
 * Compute Optimizer needs the account opted in and ~14 days of data before it returns anything,
 * and has no recommendation type for EKS managed node groups at all, so {@code AWS_EKS} VMs
 * always stay on the local rules regardless of this service.
 *
 * Since E30-T04 a daily capture ({@link #captureRecommendations()}) stores every recommendation
 * of every account and region the fleet runs in, with all options, performance risk, migration
 * effort and platform differences; the rightsizing page reads those rows and never calls AWS.
 * Enabled by default (E08-T10; {@code cost.optimizer.enabled}): read-only, not billed per request,
 * and skipped without AWS credentials. An account not opted in just has no rows.
 */
@Service
public class ComputeOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(ComputeOptimizerService.class);

    public static final String OVER_PROVISIONED = "OVER_PROVISIONED";

    @Value("${cost.optimizer.enabled:true}")
    private boolean enabled;

    /** Stored rows not refreshed by a capture for this long are dropped (the instance went away). */
    @Value("${cost.optimizer.retention-days:7}")
    private int retentionDays = 7;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean live(String key, boolean current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    /** An account's client in a region: the factory's cached one, or a fixed one in tests. */
    private final java.util.function.BiFunction<String, String, ComputeOptimizerClient> clientFactory;
    private final java.util.function.BooleanSupplier configured;
    private final ComputeOptimizerRecommendationRepository repository;
    private final VmRepository vmRepository;
    private final TransactionOperations tx;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeOptimizerService(AwsClientFactory awsClientFactory,
                                   ComputeOptimizerRecommendationRepository repository,
                                   VmRepository vmRepository,
                                   PlatformTransactionManager transactionManager) {
        this.clientFactory = (accountRef, region) -> awsClientFactory.computeOptimizer(accountRef, region);
        this.configured = awsClientFactory::isConfigured;
        this.repository = repository;
        this.vmRepository = vmRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Test seam: fixed clients and clock, credentials configured, no transactions. */
    ComputeOptimizerService(Function<String, ComputeOptimizerClient> clientFactory, java.time.Clock clock,
                            ComputeOptimizerRecommendationRepository repository, VmRepository vmRepository) {
        this.clientFactory = (accountRef, region) -> clientFactory.apply(region);
        this.configured = () -> true;
        this.repository = repository;
        this.vmRepository = vmRepository;
        this.tx = TransactionOperations.withoutTransaction();
        this.clock = clock;
    }

    /**
     * One option of a recommendation (E30-T04). {@code performanceRisk} is AWS's 0 (very low) to 4
     * (very high); {@code platformDifferences} lists e.g. Architecture for a Graviton option.
     */
    public record Option(int rank, String instanceType, BigDecimal performanceRisk, String migrationEffort,
                         List<String> platformDifferences, BigDecimal estimatedMonthlySavings) {
        public boolean changesArchitecture() {
            return platformDifferences != null && platformDifferences.stream().anyMatch("Architecture"::equalsIgnoreCase);
        }
    }

    /**
     * An instance's finding (OVER_PROVISIONED, UNDER_PROVISIONED, OPTIMIZED, NOT_OPTIMIZED) and its
     * recommended types, best rank first (E08-T05). Callers still check each option is actually
     * cheaper before suggesting it.
     */
    public record Recommendation(String instanceId, String finding, List<String> optionTypesByRank,
                                 List<Option> options, List<String> findingReasons, boolean memoryMetricPresent) {
        public Recommendation(String instanceId, String finding, List<String> optionTypesByRank) {
            this(instanceId, finding, optionTypesByRank, List.of(), List.of(), false);
        }

        /** The top-ranked option. */
        public String suggestedInstanceType() {
            return optionTypesByRank.isEmpty() ? null : optionTypesByRank.get(0);
        }

        public boolean overProvisioned() {
            return OVER_PROVISIONED.equals(finding);
        }
    }

    public boolean isAvailable() {
        return live("cost.optimizer.enabled", enabled) && configured.getAsBoolean();
    }

    public Map<String, Recommendation> getEc2Recommendations(String region) {
        return getEc2Recommendations(CloudTarget.of(region));
    }

    /**
     * The over-provisioned instances of one account in one region that have at least one option,
     * keyed by instance ID — the scale-down overlay (an under-provisioned instance's options are
     * larger, M11, E08-T05). Read from the stored capture: no AWS call.
     */
    public Map<String, Recommendation> getEc2Recommendations(CloudTarget target) {
        Map<String, Recommendation> result = new LinkedHashMap<>();
        getAllRecommendations(target).forEach((id, rec) -> {
            if (rec.overProvisioned() && !rec.optionTypesByRank().isEmpty()) {
                result.put(id, rec);
            }
        });
        return result;
    }

    /** Every stored recommendation (any finding) of one account in one region (E30-T04). */
    public Map<String, Recommendation> getAllRecommendations(CloudTarget target) {
        if (!live("cost.optimizer.enabled", enabled) || target == null || target.region() == null) {
            return Map.of();
        }
        Map<String, Recommendation> result = new LinkedHashMap<>();
        for (ComputeOptimizerRecommendation row : repository.findByRegionAndAwsAccountRef(target.region(), target.accountRef())) {
            result.put(row.getProviderVmId(), toRecommendation(row));
        }
        return result;
    }

    /**
     * Daily capture (E30-T04): for every account and region with active EC2 VMs, every page of
     * Compute Optimizer's EC2 recommendations is stored with all options, replacing that
     * instance's previous row; instances no longer reported are removed. Returns rows stored.
     */
    public int captureRecommendations() {
        if (!isAvailable()) {
            return 0;
        }
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() == CloudProvider.AWS && v.getRegion() != null)
                .toList();
        Map<String, String> vmIdByInstance = vms.stream().filter(v -> v.getProviderVmId() != null)
                .collect(Collectors.toMap(Vm::getProviderVmId, Vm::getVmId, (a, b) -> a));
        List<CloudTarget> targets = vms.stream().map(CloudTarget::of).distinct().toList();

        int stored = 0;
        for (CloudTarget target : targets) {
            try {
                List<InstanceRecommendation> recs = fetchAll(target);
                Integer n = tx.execute(status -> store(target, recs, vmIdByInstance));
                stored += n == null ? 0 : n;
            } catch (OptInRequiredException e) {
                log.warn("Compute Optimizer is not enrolled for {} — enable it in the AWS console to get its "
                        + "rightsizing recommendations; the local rules still apply.", target);
            } catch (ComputeOptimizerException e) {
                log.error("Compute Optimizer capture failed for {}: {}", target, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error capturing Compute Optimizer recommendations for {}: {}", target, e.getMessage());
            }
        }
        Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(retentionDays)));
        tx.executeWithoutResult(status -> repository.deleteByCapturedAtBefore(cutoff));
        log.info("Compute Optimizer capture: {} recommendation(s) stored across {} account/region target(s)",
                stored, targets.size());
        return stored;
    }

    /** Every page of one target's recommendations (E08-T04). */
    private List<InstanceRecommendation> fetchAll(CloudTarget target) {
        List<InstanceRecommendation> all = new ArrayList<>();
        String token = null;
        do {
            GetEc2InstanceRecommendationsResponse response = clientFactory.apply(target.accountRef(), target.region())
                    .getEC2InstanceRecommendations(GetEc2InstanceRecommendationsRequest.builder().nextToken(token).build());
            all.addAll(response.instanceRecommendations());
            token = response.nextToken();
        } while (token != null && !token.isEmpty());
        return all;
    }

    private int store(CloudTarget target, List<InstanceRecommendation> recs, Map<String, String> vmIdByInstance) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, ComputeOptimizerRecommendation> existing = new LinkedHashMap<>();
        for (ComputeOptimizerRecommendation row : repository.findByRegionAndAwsAccountRef(target.region(), target.accountRef())) {
            existing.put(row.getProviderVmId(), row);
        }
        Set<String> seen = new HashSet<>();
        List<String> ids = recs.stream().map(r -> extractInstanceId(r.instanceArn())).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            // a row of the same instance stored under another target (e.g. moved account) is replaced too
            repository.findByProviderVmIdIn(ids).forEach(r -> existing.putIfAbsent(r.getProviderVmId(), r));
        }
        int count = 0;
        for (InstanceRecommendation rec : recs) {
            String instanceId = extractInstanceId(rec.instanceArn());
            if (instanceId == null || !seen.add(instanceId)) {
                continue;
            }
            ComputeOptimizerRecommendation row = existing.get(instanceId);
            if (row == null) {
                row = new ComputeOptimizerRecommendation();
                row.setProviderVmId(instanceId);
            } else {
                row.getOptions().clear();
            }
            row.setVmId(vmIdByInstance.get(instanceId));
            row.setAwsAccountRef(target.accountRef());
            row.setRegion(target.region());
            row.setFinding(normalizeFinding(rec));
            row.setFindingReasons(rec.hasFindingReasonCodes() ? String.join(",", rec.findingReasonCodesAsStrings()) : null);
            row.setCurrentInstanceType(rec.currentInstanceType());
            row.setLookbackDays(rec.lookBackPeriodInDays() == null ? null : rec.lookBackPeriodInDays().intValue());
            row.setMemoryMetricPresent(memoryPresent(rec));
            row.setCapturedAt(now);
            for (InstanceRecommendationOption o : sortedOptions(rec)) {
                ComputeOptimizerOption option = new ComputeOptimizerOption();
                option.setOptionRank(o.rank() == null ? 0 : o.rank());
                option.setInstanceType(o.instanceType());
                option.setPerformanceRisk(o.performanceRisk() == null ? null
                        : BigDecimal.valueOf(o.performanceRisk()).setScale(2, RoundingMode.HALF_UP));
                option.setMigrationEffort(o.migrationEffortAsString());
                option.setPlatformDifferences(o.hasPlatformDifferences() && !o.platformDifferencesAsStrings().isEmpty()
                        ? String.join(",", o.platformDifferencesAsStrings()) : null);
                option.setEstimatedMonthlySavings(monthlySavings(o));
                row.addOption(option);
            }
            repository.save(row);
            count++;
        }
        // instances of this target Compute Optimizer no longer reports
        existing.values().stream()
                .filter(r -> !seen.contains(r.getProviderVmId()))
                .filter(r -> target.region().equals(r.getRegion()) && target.accountRef().equals(r.getAwsAccountRef()))
                .forEach(repository::delete);
        return count;
    }

    /**
     * The account's Compute Optimizer enrollment in a region (E08-T11): e.g. "Active" or
     * "Inactive". Free, read-only. Throws on an AWS error so the caller can report it.
     */
    public String getEnrollmentStatus(String region) {
        return getClient(region)
                .getEnrollmentStatus(software.amazon.awssdk.services.computeoptimizer.model.GetEnrollmentStatusRequest.builder().build())
                .statusAsString();
    }

    /**
     * Over-provisioned instances of one response with at least one option, keyed by instance ID
     * (extracted from each recommendation's ARN), options in rank order (E08-T05, M11).
     */
    Map<String, Recommendation> parseRecommendations(GetEc2InstanceRecommendationsResponse response) {
        Map<String, Recommendation> result = new LinkedHashMap<>();
        for (InstanceRecommendation rec : response.instanceRecommendations()) {
            String instanceId = extractInstanceId(rec.instanceArn());
            if (instanceId == null) {
                continue;
            }
            Recommendation r = toRecommendation(instanceId, rec);
            if (r.overProvisioned() && !r.optionTypesByRank().isEmpty()) {
                result.put(instanceId, r);
            }
        }
        return result;
    }

    private Recommendation toRecommendation(String instanceId, InstanceRecommendation rec) {
        List<Option> options = sortedOptions(rec).stream()
                .filter(o -> o.instanceType() != null)
                .map(o -> new Option(o.rank() == null ? 0 : o.rank(), o.instanceType(),
                        o.performanceRisk() == null ? null : BigDecimal.valueOf(o.performanceRisk()).setScale(2, RoundingMode.HALF_UP),
                        o.migrationEffortAsString(),
                        o.hasPlatformDifferences() ? List.copyOf(o.platformDifferencesAsStrings()) : List.of(),
                        monthlySavings(o)))
                .toList();
        return new Recommendation(instanceId, normalizeFinding(rec), options.stream().map(Option::instanceType).toList(),
                options, rec.hasFindingReasonCodes() ? List.copyOf(rec.findingReasonCodesAsStrings()) : List.of(),
                memoryPresent(rec));
    }

    private Recommendation toRecommendation(ComputeOptimizerRecommendation row) {
        List<Option> options = row.getOptions().stream()
                .sorted(Comparator.comparing(ComputeOptimizerOption::getOptionRank))
                .map(o -> new Option(o.getOptionRank(), o.getInstanceType(), o.getPerformanceRisk(), o.getMigrationEffort(),
                        split(o.getPlatformDifferences()), o.getEstimatedMonthlySavings()))
                .toList();
        return new Recommendation(row.getProviderVmId(), row.getFinding(), options.stream().map(Option::instanceType).toList(),
                options, split(row.getFindingReasons()), row.isMemoryMetricPresent());
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).toList();
    }

    private static List<InstanceRecommendationOption> sortedOptions(InstanceRecommendation rec) {
        if (!rec.hasRecommendationOptions()) {
            return List.of();
        }
        return rec.recommendationOptions().stream()
                .sorted(Comparator.comparing(o -> o.rank() == null ? Integer.MAX_VALUE : o.rank()))
                .toList();
    }

    private static BigDecimal monthlySavings(InstanceRecommendationOption o) {
        if (o.savingsOpportunity() == null || o.savingsOpportunity().estimatedMonthlySavings() == null
                || o.savingsOpportunity().estimatedMonthlySavings().value() == null) {
            return null;
        }
        return BigDecimal.valueOf(o.savingsOpportunity().estimatedMonthlySavings().value()).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean memoryPresent(InstanceRecommendation rec) {
        return rec.hasUtilizationMetrics() && rec.utilizationMetrics().stream()
                .anyMatch(m -> "Memory".equalsIgnoreCase(m.nameAsString()));
    }

    /** OVER_PROVISIONED, UNDER_PROVISIONED, OPTIMIZED or NOT_OPTIMIZED. */
    static String normalizeFinding(InstanceRecommendation rec) {
        if (rec.finding() == null) {
            return "UNKNOWN";
        }
        return switch (rec.finding()) {
            case OVERPROVISIONED -> OVER_PROVISIONED;
            case UNDERPROVISIONED -> "UNDER_PROVISIONED";
            case OPTIMIZED -> "OPTIMIZED";
            case NOT_OPTIMIZED -> "NOT_OPTIMIZED";
            default -> String.valueOf(rec.findingAsString()).toUpperCase(java.util.Locale.ROOT);
        };
    }

    private String extractInstanceId(String instanceArn) {
        if (instanceArn == null) {
            return null;
        }
        int slash = instanceArn.lastIndexOf('/');
        return slash >= 0 ? instanceArn.substring(slash + 1) : instanceArn;
    }

    private ComputeOptimizerClient getClient(String region) {
        return clientFactory.apply(null, region);
    }
}
