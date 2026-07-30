package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.MetricValue;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ingests real AWS billing data via Cost Explorer's {@code GetCostAndUsage}, grouped by the
 * {@code tcg:environment} cost-allocation tag (see {@link TagReconciliationService}), and writes
 * it into the {@code actualCost} column {@link CostDailySnapshot} has carried since v1 — this
 * does NOT extend {@link CostDataProvider}: that interface is per-VM, but Cost Explorer's
 * tag-grouped results are per-tag-value aggregates that can't be decomposed back to individual
 * VMs without another (billed, rate-limited) per-resource query.
 *
 * Cost Explorer's API is fixed to us-east-1 regardless of the account's operating region —
 * unlike every other AWS client in this codebase, this one is never parameterized by VM region.
 * Each {@code GetCostAndUsage} call is both rate-limited and billed (~$0.01), so this always
 * fetches every environment in one grouped call, never one call per environment, and is meant to
 * run on a daily schedule (see {@code ActualCostIngestionScheduler}), never per page-load.
 */
@Service
public class CostExplorerBillingService {

    private static final Logger log = LoggerFactory.getLogger(CostExplorerBillingService.class);
    private static final String UNBLENDED_COST_METRIC = "UnblendedCost";

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix;

    @Value("${cost.actuals.enabled:false}")
    private boolean actualsEnabled;

    private volatile CostExplorerClient client;

    private final EnvironmentRepository environmentRepository;
    private final CostDailySnapshotRepository costDailySnapshotRepository;

    public CostExplorerBillingService(EnvironmentRepository environmentRepository,
                                       CostDailySnapshotRepository costDailySnapshotRepository) {
        this.environmentRepository = environmentRepository;
        this.costDailySnapshotRepository = costDailySnapshotRepository;
    }

    public record IngestResult(int updated, int skippedNoEnvironment, int skippedNoSnapshotRow, int groupsReturned) {
    }

    public boolean isAvailable() {
        return accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    /**
     * Ingests one day's actual cost per environment. Skips the Cost Explorer call entirely (and
     * its ~$0.01/call charge) if this day already has at least one non-null actualCost recorded —
     * guards against re-billing on a scheduler restart within the same day.
     */
    public IngestResult ingestDailyActualCosts(LocalDate date) {
        if (!actualsEnabled) {
            log.debug("Actual cost ingestion disabled (cost.actuals.enabled=false) — skipping {}", date);
            return new IngestResult(0, 0, 0, 0);
        }
        if (!isAvailable()) {
            log.warn("AWS not available — skipping actual cost ingestion for {}", date);
            return new IngestResult(0, 0, 0, 0);
        }
        Date snapshotDate = Date.valueOf(date);
        if (costDailySnapshotRepository.existsBySnapshotDateAndActualCostIsNotNull(snapshotDate)) {
            log.debug("Actual costs already ingested for {} — skipping Cost Explorer call", date);
            return new IngestResult(0, 0, 0, 0);
        }

        Map<String, BigDecimal> costsByEnvironmentName;
        try {
            costsByEnvironmentName = parseCostByEnvironmentTag(fetchDailyCostByEnvironment(date));
        } catch (Exception e) {
            log.error("Failed to fetch actual costs from Cost Explorer for {}: {}", date, e.getMessage(), e);
            return new IngestResult(0, 0, 0, 0);
        }

        IngestResult result = applyActualCosts(date, costsByEnvironmentName);
        log.info("Actual cost ingestion for {} — {} updated, {} skipped (no matching environment), " +
                        "{} skipped (no estimate snapshot row yet), {} tag group(s) returned",
                date, result.updated(), result.skippedNoEnvironment(), result.skippedNoSnapshotRow(), result.groupsReturned());
        return result;
    }

    /**
     * @param days how many days back to backfill, oldest first (matches
     *             {@code CostSnapshotService.backfillHistoricalSnapshots}'s ordering)
     */
    public IngestResult backfillActualCosts(int days) {
        int updated = 0, skippedNoEnvironment = 0, skippedNoSnapshotRow = 0, groupsReturned = 0;
        for (int i = days; i >= 1; i--) {
            IngestResult dayResult = ingestDailyActualCosts(LocalDate.now().minusDays(i));
            updated += dayResult.updated();
            skippedNoEnvironment += dayResult.skippedNoEnvironment();
            skippedNoSnapshotRow += dayResult.skippedNoSnapshotRow();
            groupsReturned += dayResult.groupsReturned();
        }
        return new IngestResult(updated, skippedNoEnvironment, skippedNoSnapshotRow, groupsReturned);
    }

    // ---- pure logic (unit-testable without a real Cost Explorer call) ----

    /**
     * Persists a already-fetched/parsed cost-by-environment-name map onto the matching
     * {@link CostDailySnapshot} row for that date. A missing snapshot row (the estimate scheduler
     * hasn't run for that environment/date yet) is skipped rather than fabricated with a zero
     * estimated cost — same "never fabricate a number" discipline the estimated-cost side follows.
     */
    IngestResult applyActualCosts(LocalDate date, Map<String, BigDecimal> costsByEnvironmentName) {
        Date snapshotDate = Date.valueOf(date);
        int updated = 0, skippedNoEnvironment = 0, skippedNoSnapshotRow = 0;

        for (Map.Entry<String, BigDecimal> entry : costsByEnvironmentName.entrySet()) {
            Optional<Environment> envOpt = environmentRepository.findByName(entry.getKey());
            if (envOpt.isEmpty()) {
                skippedNoEnvironment++;
                continue;
            }
            Environment env = envOpt.get();
            Optional<CostDailySnapshot> snapshotOpt = costDailySnapshotRepository
                    .findByEnvironmentEnvironmentIdAndSnapshotDate(env.getEnvironmentId(), snapshotDate);
            if (snapshotOpt.isEmpty()) {
                skippedNoSnapshotRow++;
                continue;
            }
            CostDailySnapshot snapshot = snapshotOpt.get();
            snapshot.setActualCost(entry.getValue());
            costDailySnapshotRepository.save(snapshot);
            updated++;
        }
        return new IngestResult(updated, skippedNoEnvironment, skippedNoSnapshotRow, costsByEnvironmentName.size());
    }

    /**
     * Parses a {@code GetCostAndUsage} response grouped by the {@code tcg:environment} tag into
     * {environment name -> cost}. Cost Explorer's tag-based GroupBy returns each group's key as
     * {@code "<tagKey>$<tagValue>"} (e.g. {@code "tcg:environment$prod-01"}); an empty value after
     * the {@code $} represents resources with no tag value and is skipped, not summed into any
     * environment's total.
     */
    Map<String, BigDecimal> parseCostByEnvironmentTag(GetCostAndUsageResponse response) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (ResultByTime resultByTime : response.resultsByTime()) {
            for (Group group : resultByTime.groups()) {
                if (group.keys().isEmpty()) {
                    continue;
                }
                String rawKey = group.keys().get(0);
                int sep = rawKey.indexOf('$');
                String tagValue = sep >= 0 ? rawKey.substring(sep + 1) : rawKey;
                if (tagValue.isBlank()) {
                    continue;
                }

                MetricValue metric = group.metrics().get(UNBLENDED_COST_METRIC);
                if (metric == null || metric.amount() == null) {
                    continue;
                }
                BigDecimal amount;
                try {
                    amount = new BigDecimal(metric.amount());
                } catch (NumberFormatException e) {
                    log.warn("Could not parse Cost Explorer amount '{}' for tag value '{}'", metric.amount(), tagValue);
                    continue;
                }
                result.merge(tagValue, amount, BigDecimal::add);
            }
        }
        return result;
    }

    private GetCostAndUsageResponse fetchDailyCostByEnvironment(LocalDate date) {
        String start = date.toString();
        String end = date.plusDays(1).toString(); // CE's time period end is exclusive

        return getClient().getCostAndUsage(GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start).end(end).build())
                .granularity(Granularity.DAILY)
                .metrics(UNBLENDED_COST_METRIC)
                .groupBy(GroupDefinition.builder()
                        .type(GroupDefinitionType.TAG)
                        .key(tagKeyPrefix + "environment")
                        .build())
                .build());
    }

    private CostExplorerClient getClient() {
        CostExplorerClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = CostExplorerClient.builder()
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .overrideConfiguration(ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofSeconds(30))
                                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                                .build())
                        .build();
            }
            return client;
        }
    }
}
