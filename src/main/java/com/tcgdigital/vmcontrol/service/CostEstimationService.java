package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CostSummaryDTO;
import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.dto.SpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.TeamSpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.VmCostDetailDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricDailyRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;
import java.util.function.Function;

/**
 * Read facade for the Cost Management page. Builds a per-VM cost/utilization bundle once per
 * call (bounded by the active VM fleet size) and derives every view — summary, breakdowns, idle
 * waste, rightsizing, detail — from it, rather than re-querying per view.
 */
@Service
public class CostEstimationService {

    private static final Logger log = LoggerFactory.getLogger(CostEstimationService.class);

    private static final int COST_WINDOW_DAYS = 30;
    private static final BigDecimal RIGHTSIZING_AVG_CPU_THRESHOLD = BigDecimal.valueOf(5);
    private static final BigDecimal RIGHTSIZING_PEAK_CPU_THRESHOLD = BigDecimal.valueOf(30);

    private final VmRepository vmRepository;
    private final VmIdleSummaryRepository vmIdleSummaryRepository;
    private final VmMetricDailyRepository vmMetricDailyRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final CostDailySnapshotRepository costDailySnapshotRepository;
    private final CostDataProvider costDataProvider;
    private final PricingReferenceService pricingReferenceService;
    private final VmCostCalculator vmCostCalculator;
    private final ObjectMapper objectMapper;

    public CostEstimationService(VmRepository vmRepository,
                                  VmIdleSummaryRepository vmIdleSummaryRepository,
                                  VmMetricDailyRepository vmMetricDailyRepository,
                                  VmInventorySnapshotRepository vmInventorySnapshotRepository,
                                  CostDailySnapshotRepository costDailySnapshotRepository,
                                  CostDataProvider costDataProvider,
                                  PricingReferenceService pricingReferenceService,
                                  VmCostCalculator vmCostCalculator,
                                  ObjectMapper objectMapper) {
        this.vmRepository = vmRepository;
        this.vmIdleSummaryRepository = vmIdleSummaryRepository;
        this.vmMetricDailyRepository = vmMetricDailyRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.costDailySnapshotRepository = costDailySnapshotRepository;
        this.costDataProvider = costDataProvider;
        this.pricingReferenceService = pricingReferenceService;
        this.vmCostCalculator = vmCostCalculator;
        this.objectMapper = objectMapper;
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
        return groupSpend(buildBundles(), this::resolveTeam, this::resolveTeam);
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
            String team = resolveTeamFromMetadata(snapshot.getEnvironment().getMetadata());
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
                    idleSummary == null ? null : idleSummary.getLatestCpuUtilization()
            ));
        }
        return bundles;
    }

    /**
     * Candidacy rule: avg CPU &lt; 5% AND peak CPU &lt; 30% over the trailing 30 days. VMs
     * without at least 30 days of metric history are skipped rather than guessed at.
     */
    private List<RightsizingCandidateDTO> buildRightsizingCandidates(List<VmCostBundle> bundles) {
        List<RightsizingCandidateDTO> candidates = new ArrayList<>();

        for (VmCostBundle b : bundles) {
            if (b.avgCpu() == null || b.peakCpu() == null) {
                continue;
            }
            if (b.avgCpu().compareTo(RIGHTSIZING_AVG_CPU_THRESHOLD) >= 0
                    || b.peakCpu().compareTo(RIGHTSIZING_PEAK_CPU_THRESHOLD) >= 0) {
                continue;
            }

            String provider = b.vm().getProvider().name();
            Optional<String> suggested = pricingReferenceService.suggestSmallerType(provider, b.instanceType());
            BigDecimal currentCost = b.estimate().cost();
            BigDecimal afterCost = null;
            BigDecimal savings = null;
            boolean costKnown = false;

            if (suggested.isPresent() && b.estimate().costKnown()) {
                PricingReferenceService.PriceLookupResult afterRate =
                        pricingReferenceService.lookupHourlyRate(provider, suggested.get(), b.vm().getRegion());
                if (afterRate.priceKnown()) {
                    afterCost = vmCostCalculator.estimateCost(afterRate.hourlyRate(), b.estimate().runtimeHours(),
                            b.estimate().storageGib(), pricingReferenceService.getStorageGbMonthRate(),
                            BigDecimal.valueOf(COST_WINDOW_DAYS));
                    savings = currentCost.subtract(afterCost);
                    costKnown = true;
                }
            }

            candidates.add(new RightsizingCandidateDTO(
                    b.vm().getVmId(),
                    b.vm().getDisplayName(),
                    b.vm().getGroup().getEnvironment().getDisplayName(),
                    b.instanceType(),
                    suggested.orElse(null),
                    b.avgCpu(),
                    b.peakCpu(),
                    currentCost,
                    afterCost,
                    savings,
                    costKnown
            ));
        }

        candidates.sort(Comparator.comparing(
                (RightsizingCandidateDTO d) -> d.estimatedMonthlySavings() == null ? BigDecimal.ZERO : d.estimatedMonthlySavings()
        ).reversed());
        return candidates;
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
                b.latestCpuUtilization()
        );
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

    private String resolveTeam(VmCostBundle b) {
        return resolveTeamFromMetadata(b.vm().getGroup().getEnvironment().getMetadata());
    }

    private String resolveTeamFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "Unassigned";
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode team = node.get("ownerTeam");
            if (team != null && !team.isNull() && !team.asText().isBlank()) {
                return team.asText();
            }
        } catch (Exception e) {
            log.debug("Could not parse environment metadata JSON for team lookup: {}", e.getMessage());
        }
        return "Unassigned";
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
            BigDecimal latestCpuUtilization
    ) {}
}
