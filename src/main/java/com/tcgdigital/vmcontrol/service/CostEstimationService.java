package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostSummaryDTO;
import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.dto.SpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.TeamSpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.VmCostDetailDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricDailyRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read facade for the Cost Management page. Builds a per-VM cost/utilization bundle once per
 * call (bounded by the active VM fleet size) and derives every view — summary, breakdowns, idle
 * waste, rightsizing, detail — from it, rather than re-querying per view.
 */
@Service
public class CostEstimationService {

    private static final int COST_WINDOW_DAYS = 30;

    private final VmRepository vmRepository;
    private final VmIdleSummaryRepository vmIdleSummaryRepository;
    private final VmMetricDailyRepository vmMetricDailyRepository;
    private final VmMetricSampleRepository vmMetricSampleRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final CostDailySnapshotRepository costDailySnapshotRepository;
    private final CostDataProvider costDataProvider;
    private final PricingReferenceService pricingReferenceService;
    private final VmCostCalculator vmCostCalculator;
    private final TeamResolver teamResolver;
    private final ComputeOptimizerService computeOptimizerService;

    @Value("${rightsizing.scale-down.cpu-threshold:15}")
    private BigDecimal scaleDownCpuThreshold;

    @Value("${rightsizing.scale-down.consecutive-days:3}")
    private int scaleDownConsecutiveDays;

    @Value("${rightsizing.scale-up.cpu-threshold:70}")
    private BigDecimal scaleUpCpuThreshold;

    @Value("${rightsizing.scale-up.consecutive-minutes:15}")
    private int scaleUpConsecutiveMinutes;

    public CostEstimationService(VmRepository vmRepository,
                                  VmIdleSummaryRepository vmIdleSummaryRepository,
                                  VmMetricDailyRepository vmMetricDailyRepository,
                                  VmMetricSampleRepository vmMetricSampleRepository,
                                  VmInventorySnapshotRepository vmInventorySnapshotRepository,
                                  CostDailySnapshotRepository costDailySnapshotRepository,
                                  CostDataProvider costDataProvider,
                                  PricingReferenceService pricingReferenceService,
                                  VmCostCalculator vmCostCalculator,
                                  TeamResolver teamResolver,
                                  ComputeOptimizerService computeOptimizerService) {
        this.vmRepository = vmRepository;
        this.vmIdleSummaryRepository = vmIdleSummaryRepository;
        this.vmMetricDailyRepository = vmMetricDailyRepository;
        this.vmMetricSampleRepository = vmMetricSampleRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.costDailySnapshotRepository = costDailySnapshotRepository;
        this.costDataProvider = costDataProvider;
        this.pricingReferenceService = pricingReferenceService;
        this.vmCostCalculator = vmCostCalculator;
        this.teamResolver = teamResolver;
        this.computeOptimizerService = computeOptimizerService;
    }

    public CostSummaryDTO getSummary() {
        List<VmCostBundle> bundles = buildBundles();
        BigDecimal totalMonthlyCost = sumCost(bundles);

        List<Vm> vms = bundles.stream().map(VmCostBundle::vm).toList();
        Timestamp prevEnd = windowStart(COST_WINDOW_DAYS);
        Timestamp prevStart = windowStart(COST_WINDOW_DAYS * 2);
        BigDecimal previousPeriodCost = costDataProvider.estimateCosts(vms, prevStart, prevEnd).values().stream()
                .map(CostDataProvider.VmCostEstimate::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal changePercent = null;
        if (previousPeriodCost.compareTo(BigDecimal.ZERO) > 0) {
            changePercent = totalMonthlyCost.subtract(previousPeriodCost)
                    .divide(previousPeriodCost, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        List<VmCostBundle> idle = bundles.stream().filter(VmCostBundle::idle).toList();
        BigDecimal idleWasteCost = sumCost(idle);

        List<RightsizingCandidateDTO> candidates = buildRightsizingCandidates(bundles);
        BigDecimal rightsizingSavings = candidates.stream()
                .map(RightsizingCandidateDTO::estimatedMonthlySavings)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long costKnownCount = bundles.stream().filter(b -> b.estimate().costKnown()).count();

        return new CostSummaryDTO(
                totalMonthlyCost,
                previousPeriodCost,
                changePercent,
                idleWasteCost,
                idle.size(),
                rightsizingSavings,
                candidates.size(),
                bundles.size(),
                (int) costKnownCount,
                Timestamp.from(Instant.now())
        );
    }

    public List<SpendByDimensionDTO> getSpendByEnvironment() {
        return groupSpend(buildBundles(),
                b -> b.vm().getGroup().getEnvironment().getEnvironmentId(),
                b -> b.vm().getGroup().getEnvironment().getDisplayName());
    }

    public List<SpendByDimensionDTO> getSpendByVmType() {
        return groupSpend(buildBundles(), b -> b.vm().getVmType().name(), b -> b.vm().getVmType().name());
    }

    public List<SpendByDimensionDTO> getSpendByTeam() {
        return groupSpend(buildBundles(),
                b -> teamResolver.resolveTeam(b.vm().getGroup().getEnvironment().getMetadata()),
                b -> teamResolver.resolveTeam(b.vm().getGroup().getEnvironment().getMetadata()));
    }

    public Page<IdleWasteRowDTO> getIdleWaste(Pageable pageable) {
        List<IdleWasteRowDTO> rows = buildBundles().stream()
                .filter(VmCostBundle::idle)
                .sorted(Comparator.comparing((VmCostBundle b) -> b.estimate().cost()).reversed())
                .map(this::toIdleWasteRow)
                .toList();
        return paginate(rows, pageable);
    }

    public Page<RightsizingCandidateDTO> getRightsizingCandidates(Pageable pageable) {
        return paginate(buildRightsizingCandidates(buildBundles()), pageable);
    }

    public Page<VmCostDetailDTO> getVmCostDetail(Pageable pageable) {
        List<VmCostDetailDTO> rows = buildBundles().stream()
                .sorted(Comparator.comparing((VmCostBundle b) -> b.estimate().cost()).reversed())
                .map(this::toVmCostDetail)
                .toList();
        return paginate(rows, pageable);
    }

    public List<SpendTrendPointDTO> getSpendTrend(int days) {
        Date since = Date.valueOf(LocalDate.now().minusDays(days));
        return costDailySnapshotRepository.findDailyTotalsSince(since).stream()
                .map(t -> new SpendTrendPointDTO(t.getSnapshotDate(), nullToZero(t.getTotalEstimatedCost()), t.getTotalActualCost()))
                .toList();
    }

    /**
     * Per-team daily spend, derived from the same daily snapshots as {@link #getSpendTrend} but
     * grouped by each snapshot's environment's team instead of summed fleet-wide.
     */
    public List<TeamSpendTrendPointDTO> getSpendTrendByTeam(int days) {
        Date since = Date.valueOf(LocalDate.now().minusDays(days));
        List<CostDailySnapshot> snapshots = costDailySnapshotRepository.findWithEnvironmentSince(since);

        Map<String, Map<Date, BigDecimal>> costByTeamAndDate = new LinkedHashMap<>();
        for (CostDailySnapshot snapshot : snapshots) {
            String team = teamResolver.resolveTeam(snapshot.getEnvironment().getMetadata());
            costByTeamAndDate
                    .computeIfAbsent(team, k -> new LinkedHashMap<>())
                    .merge(snapshot.getSnapshotDate(), nullToZero(snapshot.getEstimatedCost()), BigDecimal::add);
        }

        List<TeamSpendTrendPointDTO> result = new ArrayList<>();
        for (Map.Entry<String, Map<Date, BigDecimal>> teamEntry : costByTeamAndDate.entrySet()) {
            for (Map.Entry<Date, BigDecimal> dateEntry : teamEntry.getValue().entrySet()) {
                result.add(new TeamSpendTrendPointDTO(dateEntry.getKey(), teamEntry.getKey(), dateEntry.getValue()));
            }
        }
        return result;
    }

    // ---- internal ----

    private List<VmCostBundle> buildBundles() {
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment();
        if (vms.isEmpty()) {
            return List.of();
        }

        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();
        Timestamp end = Timestamp.from(Instant.now());
        Timestamp start = windowStart(COST_WINDOW_DAYS);

        Map<String, CostDataProvider.VmCostEstimate> estimates = costDataProvider.estimateCosts(vms, start, end);

        Map<String, VmMetricDailyRepository.CpuStats> cpuStatsByVmId = new LinkedHashMap<>();
        Date sinceDate = Date.valueOf(LocalDate.now().minusDays(COST_WINDOW_DAYS));
        for (VmMetricDailyRepository.CpuStats stats : vmMetricDailyRepository.findCpuStatsSince(vmIds, sinceDate)) {
            cpuStatsByVmId.put(stats.getVmId(), stats);
        }

        Map<String, VmIdleSummary> idleByVmId = new LinkedHashMap<>();
        for (VmIdleSummary summary : vmIdleSummaryRepository.findByVmVmIdIn(vmIds)) {
            idleByVmId.put(summary.getVm().getVmId(), summary);
        }

        Map<String, String> instanceTypeByVmId = new LinkedHashMap<>();
        for (VmInventorySnapshotRepository.InstanceTypeProjection projection
                : vmInventorySnapshotRepository.findInstanceTypesByVmIds(vmIds)) {
            instanceTypeByVmId.put(projection.getVmId(), projection.getInstanceType());
        }

        Map<String, Boolean> scaleDownByVmId = computeScaleDownCandidates(vmIds);
        Map<String, Boolean> scaleUpByVmId = computeScaleUpCandidates(vmIds);

        List<VmCostBundle> bundles = new ArrayList<>();
        for (Vm vm : vms) {
            String vmId = vm.getVmId();
            CostDataProvider.VmCostEstimate estimate = estimates.get(vmId);
            VmMetricDailyRepository.CpuStats cpuStats = cpuStatsByVmId.get(vmId);
            VmIdleSummary idleSummary = idleByVmId.get(vmId);

            bundles.add(new VmCostBundle(
                    vm,
                    estimate,
                    instanceTypeByVmId.get(vmId),
                    cpuStats == null ? null : cpuStats.getAvgCpu(),
                    cpuStats == null ? null : cpuStats.getMaxCpu(),
                    idleSummary != null && Boolean.TRUE.equals(idleSummary.getIdle()),
                    idleSummary == null ? null : idleSummary.getIdleSince(),
                    idleSummary == null ? null : idleSummary.getIdleDurationMinutes(),
                    idleSummary == null ? null : idleSummary.getLatestCpuUtilization(),
                    Boolean.TRUE.equals(scaleDownByVmId.get(vmId)),
                    Boolean.TRUE.equals(scaleUpByVmId.get(vmId))
            ));
        }
        return bundles;
    }

    /**
     * Scale-down candidacy: the most recent {@code scaleDownConsecutiveDays} daily rows must
     * *each* have avg CPU below {@code scaleDownCpuThreshold} — not just the window's average —
     * so a VM that had one legitimate busy day inside an otherwise-quiet window isn't flagged.
     * Skipped (false) if fewer than that many days of daily-rollup history exist, same
     * "don't guess with incomplete data" rule the old aggregate-based check used.
     */
    private Map<String, Boolean> computeScaleDownCandidates(List<String> vmIds) {
        Date sinceDate = Date.valueOf(LocalDate.now().minusDays(scaleDownConsecutiveDays));
        List<VmMetricDaily> dailyRows =
                vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(vmIds, sinceDate);

        Map<String, List<VmMetricDaily>> rowsByVmId = dailyRows.stream()
                .collect(Collectors.groupingBy(d -> d.getVm().getVmId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<VmMetricDaily>> entry : rowsByVmId.entrySet()) {
            List<VmMetricDaily> rows = entry.getValue();
            boolean candidate = rows.size() >= scaleDownConsecutiveDays
                    && rows.stream().limit(scaleDownConsecutiveDays)
                            .allMatch(d -> d.getAvgCpuUtilization() != null
                                    && d.getAvgCpuUtilization().compareTo(scaleDownCpuThreshold) < 0);
            result.put(entry.getKey(), candidate);
        }
        return result;
    }

    /**
     * Scale-up candidacy: every raw CloudWatch sample (collected every {@code
     * cloudwatch.metric.schedule.interval} minutes, default 5) in the trailing {@code
     * scaleUpConsecutiveMinutes} window must exceed {@code scaleUpCpuThreshold}. Requires at
     * least 2 samples in the window so a single fluky high reading can't trigger a false
     * positive — with the default 5-minute sampling interval and 15-minute window that's the
     * minimum coverage that still reflects sustained (not momentary) load.
     */
    private Map<String, Boolean> computeScaleUpCandidates(List<String> vmIds) {
        Timestamp windowStart = Timestamp.from(Instant.now().minus(scaleUpConsecutiveMinutes, ChronoUnit.MINUTES));
        Timestamp windowEnd = Timestamp.from(Instant.now());
        List<VmMetricSample> samples =
                vmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(vmIds, windowStart, windowEnd);

        Map<String, List<VmMetricSample>> samplesByVmId = samples.stream()
                .collect(Collectors.groupingBy(s -> s.getVm().getVmId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<VmMetricSample>> entry : samplesByVmId.entrySet()) {
            List<VmMetricSample> vmSamples = entry.getValue();
            boolean candidate = vmSamples.size() >= 2
                    && vmSamples.stream().allMatch(s -> s.getCpuUtilization() != null
                            && s.getCpuUtilization().compareTo(scaleUpCpuThreshold) > 0);
            result.put(entry.getKey(), candidate);
        }
        return result;
    }

    /**
     * Candidacy rule: {@link #computeScaleDownCandidates} OR {@link #computeScaleUpCandidates}
     * (never both — scale-down is checked first). Only plain {@code CloudProvider.AWS} VMs are
     * eligible for either direction: {@code AWS_EKS} node-group VMs are resized via
     * launch-template/node-group updates, not a per-instance API call, and that's out of scope
     * here — such VMs never appear in this table regardless of their CPU pattern.
     *
     * Where AWS Compute Optimizer has a real recommendation for a scale-down candidate, its
     * suggested instance type is used (source={@code compute-optimizer}) instead of the local
     * downsize-map guess — it's a second, AWS-vetted signal that overlays the rule rather than
     * replacing it. Compute Optimizer integration is scale-down only; scale-up candidates and
     * {@code AWS_EKS} VMs always use the local sizing map (source={@code cpu-threshold-rule}).
     */
    private List<RightsizingCandidateDTO> buildRightsizingCandidates(List<VmCostBundle> bundles) {
        List<RightsizingCandidateDTO> candidates = new ArrayList<>();
        Map<String, ComputeOptimizerService.Recommendation> recommendationsByInstanceId =
                fetchComputeOptimizerRecommendations(bundles);

        for (VmCostBundle b : bundles) {
            if (b.vm().getProvider() != CloudProvider.AWS) {
                continue;
            }

            String direction;
            if (b.scaleDownCandidate()) {
                direction = "SCALE_DOWN";
            } else if (b.scaleUpCandidate()) {
                direction = "SCALE_UP";
            } else {
                continue;
            }

            String provider = b.vm().getProvider().name();
            boolean isScaleDown = "SCALE_DOWN".equals(direction);
            ComputeOptimizerService.Recommendation recommendation =
                    isScaleDown ? recommendationsByInstanceId.get(b.vm().getProviderVmId()) : null;

            String suggestedType;
            String source;
            String findingLevel;
            if (recommendation != null) {
                suggestedType = recommendation.suggestedInstanceType();
                source = "compute-optimizer";
                findingLevel = recommendation.finding();
            } else {
                suggestedType = isScaleDown
                        ? pricingReferenceService.suggestSmallerType(provider, b.instanceType()).orElse(null)
                        : pricingReferenceService.suggestLargerType(provider, b.instanceType()).orElse(null);
                source = "cpu-threshold-rule";
                findingLevel = null;
            }

            BigDecimal currentCost = b.estimate().cost();
            BigDecimal afterCost = null;
            BigDecimal savings = null;
            boolean costKnown = false;

            if (suggestedType != null && b.estimate().costKnown()) {
                PricingReferenceService.PriceLookupResult afterRate =
                        pricingReferenceService.lookupHourlyRate(provider, suggestedType, b.vm().getRegion());
                if (afterRate.priceKnown()) {
                    afterCost = vmCostCalculator.estimateCost(afterRate.hourlyRate(), b.estimate().runtimeHours(),
                            b.estimate().storageGib(), pricingReferenceService.getStorageGbMonthRate(),
                            BigDecimal.valueOf(COST_WINDOW_DAYS));
                    // Negative for scale-up (a cost increase) — expected, not a bug; the shared
                    // sort below naturally ranks scale-up candidates after every real saving.
                    savings = currentCost.subtract(afterCost);
                    costKnown = true;
                }
            }

            candidates.add(new RightsizingCandidateDTO(
                    b.vm().getVmId(),
                    b.vm().getDisplayName(),
                    b.vm().getGroup().getEnvironment().getDisplayName(),
                    b.instanceType(),
                    suggestedType,
                    b.avgCpu(),
                    b.peakCpu(),
                    currentCost,
                    afterCost,
                    savings,
                    costKnown,
                    source,
                    findingLevel,
                    direction,
                    b.vm().getStatus().name()
            ));
        }

        candidates.sort(Comparator.comparing(
                (RightsizingCandidateDTO d) -> d.estimatedMonthlySavings() == null ? BigDecimal.ZERO : d.estimatedMonthlySavings()
        ).reversed());
        return candidates;
    }

    /**
     * One Compute Optimizer call per distinct region among the fleet's plain-EC2 VMs (never
     * per-VM) — a no-op returning an empty map when {@code cost.optimizer.enabled=false}, so this
     * fetch is safe to leave in the hot path unconditionally.
     */
    private Map<String, ComputeOptimizerService.Recommendation> fetchComputeOptimizerRecommendations(List<VmCostBundle> bundles) {
        Map<String, ComputeOptimizerService.Recommendation> result = new LinkedHashMap<>();
        bundles.stream()
                .filter(b -> b.vm().getProvider() == CloudProvider.AWS)
                .map(b -> b.vm().getRegion())
                .distinct()
                .forEach(region -> result.putAll(computeOptimizerService.getEc2Recommendations(region)));
        return result;
    }

    private IdleWasteRowDTO toIdleWasteRow(VmCostBundle b) {
        return new IdleWasteRowDTO(
                b.vm().getVmId(),
                b.vm().getDisplayName(),
                b.vm().getGroup().getEnvironment().getDisplayName(),
                b.vm().getGroup().getDisplayName(),
                b.estimate().cost(),
                b.estimate().costKnown(),
                b.idleDurationMinutes(),
                b.idleSince(),
                b.latestCpuUtilization(),
                monthlyIdleCost(b)
        );
    }

    /**
     * Dollars burned while idle, capped to the same {@link #COST_WINDOW_DAYS} window as
     * {@code monthlyCost} so the two figures stay directly comparable (a VM idle longer than the
     * window shows the same value as monthlyCost, never more).
     */
    private BigDecimal monthlyIdleCost(VmCostBundle b) {
        if (b.estimate().hourlyRate() == null || b.idleDurationMinutes() == null) {
            return BigDecimal.ZERO;
        }
        int cappedMinutes = Math.min(b.idleDurationMinutes(), COST_WINDOW_DAYS * 24 * 60);
        BigDecimal hours = BigDecimal.valueOf(cappedMinutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return b.estimate().hourlyRate().multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }

    private VmCostDetailDTO toVmCostDetail(VmCostBundle b) {
        return new VmCostDetailDTO(
                b.vm().getVmId(),
                b.vm().getDisplayName(),
                b.vm().getGroup().getEnvironment().getDisplayName(),
                b.vm().getGroup().getDisplayName(),
                b.vm().getProvider().name(),
                b.instanceType(),
                b.vm().getRegion(),
                b.vm().getStatus().name(),
                b.estimate().runtimeHours(),
                b.estimate().cost(),
                b.estimate().costKnown(),
                b.estimate().storageGib()
        );
    }

    private List<SpendByDimensionDTO> groupSpend(List<VmCostBundle> bundles,
                                                  Function<VmCostBundle, String> keyFn,
                                                  Function<VmCostBundle, String> labelFn) {
        Map<String, BigDecimal> costByKey = new LinkedHashMap<>();
        Map<String, Integer> countByKey = new LinkedHashMap<>();
        Map<String, String> labelByKey = new LinkedHashMap<>();

        for (VmCostBundle b : bundles) {
            String key = keyFn.apply(b);
            costByKey.merge(key, b.estimate().cost(), BigDecimal::add);
            countByKey.merge(key, 1, Integer::sum);
            labelByKey.putIfAbsent(key, labelFn.apply(b));
        }

        return costByKey.entrySet().stream()
                .map(e -> new SpendByDimensionDTO(e.getKey(), labelByKey.get(e.getKey()), e.getValue(), countByKey.get(e.getKey())))
                .sorted(Comparator.comparing(SpendByDimensionDTO::cost).reversed())
                .toList();
    }

    private <T> Page<T> paginate(List<T> all, Pageable pageable) {
        int start = (int) Math.min(pageable.getOffset(), all.size());
        int end = (int) Math.min(start + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(start, end), pageable, all.size());
    }

    private Timestamp windowStart(int daysAgo) {
        return Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
    }

    private BigDecimal sumCost(List<VmCostBundle> bundles) {
        return bundles.stream().map(b -> b.estimate().cost()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record VmCostBundle(
            Vm vm,
            CostDataProvider.VmCostEstimate estimate,
            String instanceType,
            BigDecimal avgCpu,
            BigDecimal peakCpu,
            boolean idle,
            Timestamp idleSince,
            Integer idleDurationMinutes,
            BigDecimal latestCpuUtilization,
            boolean scaleDownCandidate,
            boolean scaleUpCandidate
    ) {}
}
