package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix;

    @Value("${cost.actuals.enabled:false}")
    private boolean actualsEnabled;

    private final EnvironmentRepository environmentRepository;
    private final CostDailySnapshotRepository costDailySnapshotRepository;
    private final CostExplorerClientProvider clientProvider;
    private final CostDayBoundary dayBoundary;

    public CostExplorerBillingService(EnvironmentRepository environmentRepository,
                                       CostDailySnapshotRepository costDailySnapshotRepository,
                                       CostExplorerClientProvider clientProvider,
                                       CostDayBoundary dayBoundary) {
        this.environmentRepository = environmentRepository;
        this.costDailySnapshotRepository = costDailySnapshotRepository;
        this.clientProvider = clientProvider;
        this.dayBoundary = dayBoundary;
    }

    public record IngestResult(int updated, int skippedNoEnvironment, int skippedNoSnapshotRow, int groupsReturned) {
    }

    public boolean isAvailable() {
        return clientProvider.isConfigured();
    }

    /**
     * Re-ingests the last {@code days} complete cost days (E08-T02, H18). Cost Explorer figures
     * stay provisional for about 72 hours, so every value inside the window is overwritten.
     */
    public IngestResult ingestTrailingWindow(int days) {
        LocalDate today = dayBoundary.today();
        return ingestWindow(today.minusDays(Math.max(1, days)), today);
    }

    /** One day's actuals; a thin wrapper over {@link #ingestWindow}. */
    public IngestResult ingestDailyActualCosts(LocalDate date) {
        return ingestWindow(date, date.plusDays(1));
    }

    /**
     * Backfills {@code days} complete days ending yesterday in one Cost Explorer request (plus one
     * per extra page), not one billed request per day.
     */
    public IngestResult backfillActualCosts(int days) {
        return ingestTrailingWindow(days);
    }

    /**
     * Fetches actual cost per environment for every day in [{@code startInclusive},
     * {@code endExclusive}) in one paginated, tag-grouped GetCostAndUsage request and writes
     * each day's values over whatever was stored before.
     */
    public IngestResult ingestWindow(LocalDate startInclusive, LocalDate endExclusive) {
        if (!actualsEnabled) {
            log.debug("Actual cost ingestion disabled (cost.actuals.enabled=false) — skipping {}..{}", startInclusive, endExclusive);
            return new IngestResult(0, 0, 0, 0);
        }
        if (!isAvailable()) {
            log.warn("AWS not available — skipping actual cost ingestion for {}..{}", startInclusive, endExclusive);
            return new IngestResult(0, 0, 0, 0);
        }
        if (!startInclusive.isBefore(endExclusive)) {
            return new IngestResult(0, 0, 0, 0);
        }

        Map<LocalDate, Map<String, BigDecimal>> costsByDate;
        try {
            costsByDate = parseCostByDateAndEnvironmentTag(fetchCostByEnvironment(startInclusive, endExclusive));
        } catch (Exception e) {
            log.error("Failed to fetch actual costs from Cost Explorer for {}..{}: {}",
                    startInclusive, endExclusive, e.getMessage(), e);
            return new IngestResult(0, 0, 0, 0);
        }

        int updated = 0, skippedNoEnvironment = 0, skippedNoSnapshotRow = 0, groupsReturned = 0;
        for (Map.Entry<LocalDate, Map<String, BigDecimal>> day : costsByDate.entrySet()) {
            IngestResult r = applyActualCosts(day.getKey(), day.getValue());
            updated += r.updated();
            skippedNoEnvironment += r.skippedNoEnvironment();
            skippedNoSnapshotRow += r.skippedNoSnapshotRow();
            groupsReturned += r.groupsReturned();
        }
        IngestResult result = new IngestResult(updated, skippedNoEnvironment, skippedNoSnapshotRow, groupsReturned);
        log.info("Actual cost ingestion for {}..{} — {} updated, {} skipped (no matching environment), " +
                        "{} skipped (no estimate snapshot row yet), {} tag group(s) returned",
                startInclusive, endExclusive, result.updated(), result.skippedNoEnvironment(),
                result.skippedNoSnapshotRow(), result.groupsReturned());
        return result;
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
            addGroups(resultByTime, result);
        }
        return result;
    }

    /**
     * Per-day variant (E08-T02): {date -> {environment name -> cost}}, each date taken from its
     * ResultByTime's time period start.
     */
    Map<LocalDate, Map<String, BigDecimal>> parseCostByDateAndEnvironmentTag(List<ResultByTime> results) {
        Map<LocalDate, Map<String, BigDecimal>> byDate = new java.util.TreeMap<>();
        for (ResultByTime resultByTime : results) {
            if (resultByTime.timePeriod() == null || resultByTime.timePeriod().start() == null) {
                continue;
            }
            LocalDate date = LocalDate.parse(resultByTime.timePeriod().start());
            addGroups(resultByTime, byDate.computeIfAbsent(date, d -> new LinkedHashMap<>()));
        }
        return byDate;
    }

    private void addGroups(ResultByTime resultByTime, Map<String, BigDecimal> result) {
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

    /** Every page of one DAILY, tag-grouped request for [start, end) (CE's end is exclusive). */
    private List<ResultByTime> fetchCostByEnvironment(LocalDate startInclusive, LocalDate endExclusive) {
        List<ResultByTime> results = new java.util.ArrayList<>();
        String token = null;
        do {
            GetCostAndUsageResponse page = clientProvider.client().getCostAndUsage(GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startInclusive.toString()).end(endExclusive.toString()).build())
                    .granularity(Granularity.DAILY)
                    .metrics(UNBLENDED_COST_METRIC)
                    .groupBy(GroupDefinition.builder()
                            .type(GroupDefinitionType.TAG)
                            .key(tagKeyPrefix + "environment")
                            .build())
                    .nextPageToken(token)
                    .build());
            results.addAll(page.resultsByTime());
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return results;
    }
}
