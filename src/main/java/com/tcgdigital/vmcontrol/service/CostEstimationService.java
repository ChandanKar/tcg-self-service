package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostSummaryDTO;
import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingOptionDTO;
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
import java.util.Optional;
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

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private int live(String key, int current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    private java.math.BigDecimal live(String key, java.math.BigDecimal current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    /** Memory-aware rightsizing (E30-T03); false returns the CPU-only rules. */
    @Value("${rightsizing.memory.enabled:true}")
    private boolean memoryRightsizingEnabled = true;

    @Value("${rightsizing.scale-down.memory-peak-threshold:60}")
    private BigDecimal scaleDownMemoryPeakThreshold = BigDecimal.valueOf(60);

    @Value("${rightsizing.scale-up.memory-threshold:85}")
    private BigDecimal scaleUpMemoryThreshold = BigDecimal.valueOf(85);

    @Value("${rightsizing.memory.headroom-percent:20}")
    private int memoryHeadroomPercent = 20;

    private boolean memoryEnabled() {
        return settings == null ? memoryRightsizingEnabled : settings.overrideOr("rightsizing.memory.enabled", memoryRightsizingEnabled);
    }

    /** Why a VM is (or is not) a candidate in one direction, with its reason codes (E30-T03). */
    private record Candidacy(boolean candidate, List<String> reasons) {
        static final Candidacy NONE = new Candidacy(false, List.of());
    }

    /** Days after an instance-type change during which Compute Optimizer advice is ignored (E08-T06). */
    @Value("${rightsizing.post-resize-cooldown-days:14}")
    private int postResizeCooldownDays = 14;

    /** How long built bundles are reused across the page's burst of requests (E08-T04, M12). */
    @Value("${cost.bundle-cache-seconds:60}")
    private long bundleCacheSeconds = 60;

    private record BundleCache(Instant builtAt, List<VmCostBundle> bundles) {
    }

    private volatile BundleCache bundleCache;

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
        List<Vm> vms = bundles.stream().map(VmCostBundle::vm).toList();
        // The EKS control-plane fee belongs to the environment, not to a node group (E26-T03).
        BigDecimal eksControlPlaneCost = sumFees(vms, windowStart(COST_WINDOW_DAYS), Timestamp.from(Instant.now()));
        BigDecimal totalMonthlyCost = sumCost(bundles).add(eksControlPlaneCost);

        Timestamp prevEnd = windowStart(COST_WINDOW_DAYS);
        Timestamp prevStart = windowStart(COST_WINDOW_DAYS * 2);
        BigDecimal previousPeriodCost = costDataProvider.estimateCosts(vms, prevStart, prevEnd).values().stream()
                .map(CostDataProvider.VmCostEstimate::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(sumFees(vms, prevStart, prevEnd));

        BigDecimal changePercent = null;
        if (previousPeriodCost.compareTo(BigDecimal.ZERO) > 0) {
            changePercent = totalMonthlyCost.subtract(previousPeriodCost)
                    .divide(previousPeriodCost, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        // KPI tiles add up the same numbers their tables show (E08-T04, M12).
        List<VmCostBundle> idle = bundles.stream().filter(VmCostBundle::idle).toList();
        BigDecimal idleWasteCost = idle.stream().map(this::monthlyIdleCost).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<RightsizingCandidateDTO> allCandidates = buildRightsizingCandidates(bundles);
        List<RightsizingCandidateDTO> candidates = allCandidates.stream()
                .filter(c -> "SCALE_DOWN".equals(c.direction()))
                .filter(c -> c.estimatedMonthlySavings() != null && c.estimatedMonthlySavings().signum() > 0)
                .toList();
        BigDecimal rightsizingSavings = candidates.stream()
                .map(RightsizingCandidateDTO::estimatedMonthlySavings)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int scaleUpCandidateCount = (int) allCandidates.stream().filter(c -> "SCALE_UP".equals(c.direction())).count();

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
                Timestamp.from(Instant.now()),
                scaleUpCandidateCount,
                (int) bundles.stream().filter(b -> b.estimate() != null && b.estimate().priceApproximate()).count(),
                eksControlPlaneCost
        );
    }

    private BigDecimal sumFees(List<Vm> vms, Timestamp start, Timestamp end) {
        Map<String, BigDecimal> fees = costDataProvider.environmentFees(vms, start, end);
        return fees == null ? BigDecimal.ZERO : fees.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<SpendByDimensionDTO> getSpendByEnvironment() {
        List<VmCostBundle> bundles = buildBundles();
        List<SpendByDimensionDTO> rows = groupSpend(bundles,
                b -> b.vm().getGroup().getEnvironment().getEnvironmentId(),
                b -> b.vm().getGroup().getEnvironment().getDisplayName());
        Map<String, BigDecimal> fees = costDataProvider.environmentFees(bundles.stream().map(VmCostBundle::vm).toList(),
                windowStart(COST_WINDOW_DAYS), Timestamp.from(Instant.now()));
        if (fees == null || fees.isEmpty()) {
            return rows;
        }
        // An EKS environment's bar includes its control-plane fee (E26-T03).
        return rows.stream()
                .map(r -> fees.containsKey(r.dimensionKey())
                        ? new SpendByDimensionDTO(r.dimensionKey(), r.dimensionLabel(), r.cost().add(fees.get(r.dimensionKey())), r.vmCount())
                        : r)
                .sorted(Comparator.comparing(SpendByDimensionDTO::cost).reversed())
                .toList();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.aws.AwsAccountService awsAccountService;

    /** Estimated spend per AWS account (E28-T11), the same totals as spend by environment. */
    public List<SpendByDimensionDTO> getSpendByAccount() {
        Map<String, String> names = awsAccountService == null ? Map.of() : awsAccountService.namesByRef();
        return groupSpend(buildBundles(),
                b -> CloudTarget.of(b.vm()).accountRef(),
                b -> names.getOrDefault(CloudTarget.of(b.vm()).accountRef(), CloudTarget.of(b.vm()).accountRef()));
    }

    public List<SpendByDimensionDTO> getSpendByVmType() {
        return groupSpend(buildBundles(), b -> b.vm().getVmType().name(), b -> b.vm().getVmType().name());
    }

    public Page<IdleWasteRowDTO> getIdleWaste(Pageable pageable) {
        List<IdleWasteRowDTO> rows = buildBundles().stream()
                .filter(VmCostBundle::idle)
                .sorted(Comparator.comparing((VmCostBundle b) -> b.estimate().cost()).reversed())
                .map(this::toIdleWasteRow)
                .toList();
        Page<IdleWasteRowDTO> page = paginate(rows, pageable);
        Map<String, com.tcgdigital.vmcontrol.model.ExternalTicket> tickets = jiraTickets(
                com.tcgdigital.vmcontrol.model.ExternalTicket.SourceType.IDLE, page.getContent().stream().map(IdleWasteRowDTO::vmId).toList());
        return tickets.isEmpty() ? page : page.map(r -> {
            com.tcgdigital.vmcontrol.model.ExternalTicket t = tickets.get(r.vmId());
            return t == null ? r : r.withJira(t.getIssueKey(), t.getUrl());
        });
    }

    public Page<RightsizingCandidateDTO> getRightsizingCandidates(Pageable pageable) {
        return getRightsizingCandidates(null, pageable);
    }

    /** Candidates of one direction (SCALE_DOWN, SCALE_UP, MODERNIZE) or all when null (E30-T05). */
    public Page<RightsizingCandidateDTO> getRightsizingCandidates(String direction, Pageable pageable) {
        List<RightsizingCandidateDTO> all = buildRightsizingCandidates(buildBundles());
        if (direction != null && !direction.isBlank()) {
            all = all.stream().filter(c -> direction.equalsIgnoreCase(c.direction())).toList();
        }
        Page<RightsizingCandidateDTO> page = paginate(all, pageable);
        Map<String, com.tcgdigital.vmcontrol.model.ExternalTicket> tickets = jiraTickets(
                com.tcgdigital.vmcontrol.model.ExternalTicket.SourceType.RIGHTSIZING,
                page.getContent().stream().map(RightsizingCandidateDTO::vmId).toList());
        return tickets.isEmpty() ? page : page.map(r -> {
            com.tcgdigital.vmcontrol.model.ExternalTicket t = tickets.get(r.vmId());
            return t == null ? r : r.withJira(t.getIssueKey(), t.getUrl());
        });
    }

    /** Jira tickets (E24-T07) for one page of sources, in one query; empty without the repository. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.repository.ExternalTicketRepository externalTicketRepository;

    private Map<String, com.tcgdigital.vmcontrol.model.ExternalTicket> jiraTickets(
            com.tcgdigital.vmcontrol.model.ExternalTicket.SourceType type, List<String> vmIds) {
        if (externalTicketRepository == null || vmIds.isEmpty()) {
            return Map.of();
        }
        Map<String, com.tcgdigital.vmcontrol.model.ExternalTicket> out = new java.util.HashMap<>();
        externalTicketRepository.findBySourceTypeAndSystemAndSourceIdIn(type, "JIRA", vmIds)
                .forEach(t -> out.put(t.getSourceId(), t));
        return out;
    }

    public Page<VmCostDetailDTO> getVmCostDetail(Pageable pageable) {
        List<VmCostDetailDTO> rows = buildBundles().stream()
                .sorted(Comparator.comparing((VmCostBundle b) -> b.estimate().cost()).reversed())
                .map(this::toVmCostDetail)
                .toList();
        return paginate(rows, pageable);
    }

    /**
     * Total VM running-hours per environment id over an arbitrary window, derived from
     * state-transition history — the same runtime basis the cost figures use. Only active VMs
     * count; environments with no active VMs (or no runtime in the window) are absent from the
     * map. Feeds the Weekly Cost Report's uptime column.
     */
    public Map<String, BigDecimal> getUptimeHoursByEnvironment(Timestamp windowStart, Timestamp windowEnd) {
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment();
        if (vms.isEmpty()) {
            return Map.of();
        }
        Map<String, CostDataProvider.VmCostEstimate> estimates =
                costDataProvider.estimateCosts(vms, windowStart, windowEnd);
        Map<String, BigDecimal> uptimeByEnvironment = new LinkedHashMap<>();
        for (Vm vm : vms) {
            CostDataProvider.VmCostEstimate estimate = estimates.get(vm.getVmId());
            if (estimate == null || estimate.runtimeHours() == null) {
                continue;
            }
            String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
            uptimeByEnvironment.merge(environmentId, estimate.runtimeHours(), BigDecimal::add);
        }
        return uptimeByEnvironment;
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
            String team = teamResolver.resolveTeam(snapshot.getEnvironment());
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

    /**
     * The current rightsizing candidate for one VM, from the cached bundles (E08-T05): what an
     * Apply must match.
     */
    /**
     * What each VM could still save (E17-T05), from the cached bundles: its monthly cost, its idle
     * cost if idle, and the saving of a SCALE_DOWN recommendation. Potential savings take the
     * largest of these per VM so nothing is counted twice.
     */
    public record VmOpportunity(String vmId, String environmentId, String groupId, String team,
                                BigDecimal monthlyCost, boolean costKnown, boolean idle, BigDecimal monthlyIdleCost,
                                BigDecimal rightsizingSaving, boolean production, boolean leaseEnabled) {
    }

    public List<VmOpportunity> listVmOpportunities() {
        List<VmCostBundle> bundles = buildBundles();
        Map<String, BigDecimal> downsizeSaving = new java.util.HashMap<>();
        for (RightsizingCandidateDTO c : buildRightsizingCandidates(bundles)) {
            if ("SCALE_DOWN".equals(c.direction()) && c.estimatedMonthlySavings() != null
                    && c.estimatedMonthlySavings().signum() > 0) {
                downsizeSaving.merge(c.vmId(), c.estimatedMonthlySavings(), BigDecimal::max);
            }
        }
        return bundles.stream().map(b -> {
            com.tcgdigital.vmcontrol.model.Environment env = b.vm().getGroup().getEnvironment();
            return new VmOpportunity(b.vm().getVmId(), env.getEnvironmentId(), b.vm().getGroup().getGroupId(),
                    teamResolver.resolveTeam(env), b.estimate().cost(), b.estimate().costKnown(),
                    b.idle(), b.idle() ? monthlyIdleCost(b) : BigDecimal.ZERO,
                    downsizeSaving.getOrDefault(b.vm().getVmId(), BigDecimal.ZERO),
                    Boolean.TRUE.equals(env.getIsProduction()), Boolean.TRUE.equals(env.getLeaseEnabled()));
        }).toList();
    }

    public Optional<RightsizingCandidateDTO> findCandidate(String vmId) {
        return buildRightsizingCandidates(buildBundles()).stream()
                .filter(c -> c.vmId().equals(vmId))
                .findFirst();
    }

    /** The best-ranked option priced below the current hourly rate, or null. */
    private String firstCheaperOption(ComputeOptimizerService.Recommendation recommendation, String provider,
                                      String region, BigDecimal currentHourlyRate) {
        if (currentHourlyRate == null) {
            return null;
        }
        for (String type : recommendation.optionTypesByRank()) {
            PricingReferenceService.PriceLookupResult rate = pricingReferenceService.lookupHourlyRate(provider, type, region);
            if (rate.priceKnown() && rate.hourlyRate().compareTo(currentHourlyRate) < 0) {
                return type;
            }
        }
        return null;
    }

    /** Drop the cached bundles, e.g. after a backfill or a resize (E08-T04). */
    public void invalidateBundles() {
        bundleCache = null;
    }

    /**
     * The per-VM bundles, rebuilt at most every cost.bundle-cache-seconds (E08-T04, M12): the
     * cost page calls six bundle-backed endpoints at once, and each used to rebuild them.
     */
    private List<VmCostBundle> buildBundles() {
        BundleCache cached = bundleCache;
        if (fresh(cached)) {
            return cached.bundles();
        }
        synchronized (this) {
            cached = bundleCache;
            if (fresh(cached)) {
                return cached.bundles();
            }
            List<VmCostBundle> bundles = List.copyOf(computeBundles());
            bundleCache = new BundleCache(Instant.now(), bundles);
            return bundles;
        }
    }

    private boolean fresh(BundleCache cached) {
        return cached != null && bundleCacheSeconds > 0
                && cached.builtAt().plusSeconds(bundleCacheSeconds).isAfter(Instant.now());
    }

    private List<VmCostBundle> computeBundles() {
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment();
        if (vms.isEmpty()) {
            return List.of();
        }

        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();
        Timestamp end = Timestamp.from(Instant.now());
        Timestamp start = windowStart(COST_WINDOW_DAYS);

        Map<String, CostDataProvider.VmCostEstimate> estimates = costDataProvider.estimateCosts(vms, start, end);

        Map<String, VmMetricDailyRepository.UtilizationStats> cpuStatsByVmId = new LinkedHashMap<>();
        Date sinceDate = Date.valueOf(LocalDate.now().minusDays(COST_WINDOW_DAYS));
        for (VmMetricDailyRepository.UtilizationStats stats : vmMetricDailyRepository.findUtilizationStatsSince(vmIds, sinceDate)) {
            cpuStatsByVmId.put(stats.getVmId(), stats);
        }

        Map<String, VmIdleSummary> idleByVmId = new LinkedHashMap<>();
        for (VmIdleSummary summary : vmIdleSummaryRepository.findByVmVmIdIn(vmIds)) {
            idleByVmId.put(summary.getVm().getVmId(), summary);
        }

        Map<String, String> instanceTypeByVmId = new LinkedHashMap<>();
        Map<String, Integer> memoryMibByVmId = new LinkedHashMap<>();
        Map<String, String> architectureByVmId = new LinkedHashMap<>();
        Map<String, Timestamp> typeChangedAtByVmId = new LinkedHashMap<>();
        for (VmInventorySnapshotRepository.InstanceTypeProjection projection
                : vmInventorySnapshotRepository.findInstanceTypesByVmIds(vmIds)) {
            instanceTypeByVmId.put(projection.getVmId(), projection.getInstanceType());
            if (projection.getArchitecture() != null) {
                architectureByVmId.put(projection.getVmId(), projection.getArchitecture());
            }
            if (projection.getMemoryMib() != null && projection.getMemoryMib() > 0) {
                memoryMibByVmId.put(projection.getVmId(), projection.getMemoryMib());
            }
            if (projection.getInstanceTypeChangedAt() != null) {
                typeChangedAtByVmId.put(projection.getVmId(), projection.getInstanceTypeChangedAt());
            }
        }

        Map<String, Candidacy> scaleDownByVmId = computeScaleDownCandidates(vmIds, typeChangedAtByVmId);
        Map<String, Candidacy> scaleUpByVmId = computeScaleUpCandidates(vmIds);

        List<VmCostBundle> bundles = new ArrayList<>();
        for (Vm vm : vms) {
            String vmId = vm.getVmId();
            CostDataProvider.VmCostEstimate estimate = estimates.get(vmId);
            VmMetricDailyRepository.UtilizationStats cpuStats = cpuStatsByVmId.get(vmId);
            VmIdleSummary idleSummary = idleByVmId.get(vmId);

            // An EKS node group has no inventory snapshot: its node type comes from its metadata (E26-T02).
            String instanceType = vm.getProvider() == CloudProvider.AWS_EKS
                    ? EstimatedCostProvider.eksNodeInfo(vm.getMetadata()).instanceType() : instanceTypeByVmId.get(vmId);
            Candidacy down = scaleDownByVmId.getOrDefault(vmId, Candidacy.NONE);
            Candidacy up = scaleUpByVmId.getOrDefault(vmId, Candidacy.NONE);
            Integer memoryMib = memoryMibByVmId.get(vmId);
            if (memoryMib == null) {
                memoryMib = pricingReferenceService.getSpec(instanceType)
                        .map(PricingReferenceService.InstanceSpec::memoryMib).orElse(null);
            }
            bundles.add(new VmCostBundle(
                    vm,
                    estimate,
                    instanceType,
                    cpuStats == null ? null : cpuStats.getAvgCpu(),
                    cpuStats == null ? null : cpuStats.getMaxCpu(),
                    cpuStats == null ? null : cpuStats.getAvgMemory(),
                    cpuStats == null ? null : cpuStats.getMaxMemory(),
                    idleSummary != null && Boolean.TRUE.equals(idleSummary.getIdle()),
                    idleSummary == null ? null : idleSummary.getIdleSince(),
                    idleSummary == null ? null : idleSummary.getIdleDurationMinutes(),
                    idleSummary == null ? null : idleSummary.getLatestCpuUtilization(),
                    down.candidate(),
                    up.candidate(),
                    typeChangedAtByVmId.get(vmId),
                    memoryMib,
                    down.candidate() ? down.reasons() : up.reasons(),
                    architectureByVmId.get(vmId)
            ));
        }
        return bundles;
    }

    /**
     * Scale-down candidacy: the most recent {@code scaleDownConsecutiveDays} daily rows must
     * *each* have avg CPU below {@code scaleDownCpuThreshold} — not just the window's average —
     * so a VM that had one legitimate busy day inside an otherwise-quiet window isn't flagged.
     * Skipped (false) if fewer than that many days of daily-rollup history exist, same
     * "don't guess with incomplete data" rule the old aggregate-based check used. Days on or
     * before the VM's last instance-type change measure the old size and are ignored (E08-T06).
     * With memory-aware rightsizing on and memory reported for those days, each day's memory peak
     * must also stay below {@code rightsizing.scale-down.memory-peak-threshold} (E30-T03).
     */
    private Map<String, Candidacy> computeScaleDownCandidates(List<String> vmIds, Map<String, Timestamp> typeChangedAtByVmId) {
        Date sinceDate = Date.valueOf(LocalDate.now().minusDays(live("rightsizing.scale-down.consecutive-days", scaleDownConsecutiveDays)));
        List<VmMetricDaily> dailyRows =
                vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(vmIds, sinceDate);

        Map<String, List<VmMetricDaily>> rowsByVmId = dailyRows.stream()
                .collect(Collectors.groupingBy(d -> d.getVm().getVmId(), LinkedHashMap::new, Collectors.toList()));

        int days = live("rightsizing.scale-down.consecutive-days", scaleDownConsecutiveDays);
        boolean memoryOn = memoryEnabled();
        BigDecimal memoryPeak = live("rightsizing.scale-down.memory-peak-threshold", scaleDownMemoryPeakThreshold);
        Map<String, Candidacy> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<VmMetricDaily>> entry : rowsByVmId.entrySet()) {
            List<VmMetricDaily> rows = entry.getValue();
            Timestamp changedAt = typeChangedAtByVmId.get(entry.getKey());
            if (changedAt != null) {
                LocalDate changeDay = changedAt.toLocalDateTime().toLocalDate();
                rows = rows.stream()
                        .filter(d -> d.getBucketDate() != null && d.getBucketDate().toLocalDate().isAfter(changeDay))
                        .toList();
            }
            boolean cpuLow = rows.size() >= days
                    && rows.stream().limit(days)
                            .allMatch(d -> d.getAvgCpuUtilization() != null
                                    && d.getAvgCpuUtilization().compareTo(live("rightsizing.scale-down.cpu-threshold", scaleDownCpuThreshold)) < 0);
            if (!cpuLow) {
                result.put(entry.getKey(), Candidacy.NONE);
                continue;
            }
            if (!memoryOn) {
                result.put(entry.getKey(), new Candidacy(true, List.of("CPU_LOW")));
                continue;
            }
            List<VmMetricDaily> recent = rows.stream().limit(days).toList();
            boolean memoryReported = recent.stream()
                    .anyMatch(d -> d.getMemorySampleCount() != null && d.getMemorySampleCount() > 0);
            if (!memoryReported) {
                result.put(entry.getKey(), new Candidacy(true, List.of("CPU_LOW", "MEMORY_UNKNOWN")));
                continue;
            }
            boolean memoryLow = recent.stream().allMatch(d -> d.getMaxMemoryUtilization() != null
                    && d.getMaxMemoryUtilization().compareTo(memoryPeak) < 0);
            result.put(entry.getKey(), memoryLow ? new Candidacy(true, List.of("CPU_LOW", "MEMORY_LOW")) : Candidacy.NONE);
        }
        return result;
    }

    /**
     * Scale-up candidacy: every raw CloudWatch sample (collected every {@code
     * cloudwatch.metric.schedule.interval} minutes, default 5) in the trailing {@code
     * scaleUpConsecutiveMinutes} window must exceed {@code scaleUpCpuThreshold}. Requires at
     * least 2 samples in the window so a single fluky high reading can't trigger a false
     * positive — with the default 5-minute sampling interval and 15-minute window that's the
     * minimum coverage that still reflects sustained (not momentary) load. Memory above
     * {@code rightsizing.scale-up.memory-threshold} in every sample qualifies the same way (E30-T03).
     */
    private Map<String, Candidacy> computeScaleUpCandidates(List<String> vmIds) {
        Timestamp windowStart = Timestamp.from(Instant.now().minus(live("rightsizing.scale-up.consecutive-minutes", scaleUpConsecutiveMinutes), ChronoUnit.MINUTES));
        Timestamp windowEnd = Timestamp.from(Instant.now());
        List<VmMetricSample> samples =
                vmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(vmIds, windowStart, windowEnd);

        Map<String, List<VmMetricSample>> samplesByVmId = samples.stream()
                .collect(Collectors.groupingBy(s -> s.getVm().getVmId(), LinkedHashMap::new, Collectors.toList()));

        boolean memoryOn = memoryEnabled();
        BigDecimal memoryHigh = live("rightsizing.scale-up.memory-threshold", scaleUpMemoryThreshold);
        Map<String, Candidacy> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<VmMetricSample>> entry : samplesByVmId.entrySet()) {
            List<VmMetricSample> vmSamples = entry.getValue();
            boolean cpuHigh = vmSamples.size() >= 2
                    && vmSamples.stream().allMatch(s -> s.getCpuUtilization() != null
                            && s.getCpuUtilization().compareTo(live("rightsizing.scale-up.cpu-threshold", scaleUpCpuThreshold)) > 0);
            boolean memHigh = memoryOn && vmSamples.size() >= 2
                    && vmSamples.stream().allMatch(s -> s.getMemoryUtilization() != null
                            && s.getMemoryUtilization().compareTo(memoryHigh) > 0);
            List<String> reasons = new ArrayList<>();
            if (cpuHigh) reasons.add("CPU_HIGH");
            if (memHigh) reasons.add("MEMORY_HIGH");
            result.put(entry.getKey(), reasons.isEmpty() ? Candidacy.NONE : new Candidacy(true, List.copyOf(reasons)));
        }
        return result;
    }

    /**
     * One recommendation per VM from the local rules and AWS Compute Optimizer (E30-T05).
     * Direction: {@link #computeScaleDownCandidates} SCALE_DOWN, else {@link #computeScaleUpCandidates}
     * SCALE_UP, else an instance Compute Optimizer finds over-provisioned with a cheaper option
     * (SCALE_DOWN, agreement ONLY_AWS), else MODERNIZE when the same-architecture newer generation
     * costs the same or less per hour. Plain {@code CloudProvider.AWS} VMs and, since E26-T02,
     * {@code AWS_EKS} node groups (priced as N EC2 nodes) are eligible; a node group is resized in
     * EKS, so its row is advice only (not applicable here) and Compute Optimizer has no opinion on it.
     *
     * For a scale-down, a cheaper Compute Optimizer option wins over the local downsize map
     * (source={@code compute-optimizer}); every candidate also lists its alternatives (newer
     * generation, Graviton, every Compute Optimizer option) and whether AWS and the rules agree.
     */
    private List<RightsizingCandidateDTO> buildRightsizingCandidates(List<VmCostBundle> bundles) {
        List<RightsizingCandidateDTO> candidates = new ArrayList<>();
        Map<String, ComputeOptimizerService.Recommendation> recommendationsByInstanceId =
                fetchComputeOptimizerRecommendations(bundles);

        for (VmCostBundle b : bundles) {
            if (b.vm().getProvider() != CloudProvider.AWS && b.vm().getProvider() != CloudProvider.AWS_EKS) {
                continue;
            }

            // EKS nodes are plain EC2 instances: price them as AWS, times the node count (E26-T02).
            boolean eks = b.vm().getProvider() == CloudProvider.AWS_EKS;
            String provider = eks ? CloudProvider.AWS.name() : b.vm().getProvider().name();
            int nodes = eks ? EstimatedCostProvider.eksNodeInfo(b.vm().getMetadata()).nodeCount() : 1;
            String region = b.vm().getRegion();
            // Compute Optimizer needs ~14 days on the new size: ignore it right after a resize (E08-T06).
            boolean inCooldown = b.instanceTypeChangedAt() != null && b.instanceTypeChangedAt().toInstant()
                    .isAfter(Instant.now().minus(java.time.Duration.ofDays(live("rightsizing.post-resize-cooldown-days", postResizeCooldownDays))));
            ComputeOptimizerService.Recommendation co =
                    eks || inCooldown ? null : recommendationsByInstanceId.get(b.vm().getProviderVmId());
            // A Compute Optimizer option counts only if it is actually cheaper (E08-T05, M11).
            String cheaperOption = co == null || !co.overProvisioned() ? null
                    : firstCheaperOption(co, provider, region, b.estimate().hourlyRate());

            String direction;
            List<String> reasons = new ArrayList<>(b.reasons());
            boolean ruleCandidate = true;
            String modernizeTarget = null;
            if (b.scaleDownCandidate()) {
                direction = "SCALE_DOWN";
            } else if (b.scaleUpCandidate()) {
                direction = "SCALE_UP";
            } else if (cheaperOption != null) {
                direction = "SCALE_DOWN";
                ruleCandidate = false;
                reasons = new ArrayList<>(List.of("AWS_OVER_PROVISIONED"));
            } else if (!eks && (modernizeTarget = modernizeTarget(b, provider)) != null) {
                direction = "MODERNIZE";
                reasons = new ArrayList<>(List.of("NEWER_GENERATION"));
            } else {
                continue;
            }
            boolean isScaleDown = "SCALE_DOWN".equals(direction);

            String suggestedType;
            String source;
            String findingLevel = null;
            if (modernizeTarget != null) {
                suggestedType = modernizeTarget;
                source = "newer-generation";
            } else if (isScaleDown && cheaperOption != null) {
                suggestedType = cheaperOption;
                source = "compute-optimizer";
                findingLevel = co.finding();
            } else {
                suggestedType = isScaleDown
                        ? pricingReferenceService.suggestSmallerType(provider, b.instanceType()).orElse(null)
                        : pricingReferenceService.suggestLargerType(provider, b.instanceType()).orElse(null);
                source = "cpu-threshold-rule";
            }

            // A smaller type must hold the observed memory peak plus headroom (E30-T03).
            boolean wouldNotFit = isScaleDown && suggestedType != null && !memoryFits(b, suggestedType);
            if (wouldNotFit) {
                reasons.add("MEMORY_WOULD_NOT_FIT");
                suggestedType = null;
            }

            BigDecimal currentCost = b.estimate().cost();
            Priced priced = price(b, provider, nodes, suggestedType);
            BigDecimal afterCost = priced == null ? null : priced.monthlyCost();
            // Negative for scale-up (a cost increase) — expected, not a bug; the shared sort below
            // naturally ranks scale-up candidates after every real saving.
            BigDecimal savings = priced == null ? null : priced.savings();
            boolean costKnown = priced != null;
            boolean approximate = b.estimate().priceApproximate() || (priced != null && priced.approximate());
            // A scale-down that saves nothing is not a recommendation (E08-T05); one dropped for
            // memory stays listed without a suggestion so admins see why.
            if (isScaleDown && !wouldNotFit && (savings == null || savings.signum() <= 0)) {
                continue;
            }

            String architecture = currentArchitecture(b);
            String chosenKind = modernizeTarget != null ? "NEW_GEN" : "RIGHTSIZE";
            List<RightsizingOptionDTO> alternatives = alternatives(b, provider, nodes, eks, isScaleDown, suggestedType,
                    chosenKind, source, co, architecture);
            String applicableReason = eks ? "Change the node group in EKS"
                    : suggestedType == null ? "No suggestion"
                    : architectureChangeReason(architecture, suggestedType);

            candidates.add(new RightsizingCandidateDTO(
                    b.vm().getVmId(),
                    b.vm().getDisplayName(),
                    b.vm().getGroup().getEnvironment().getEnvironmentId(),
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
                    b.vm().getStatus().name(),
                    approximate
            ).withNodeCount(eks ? nodes : null)
                    .withMemory(b.avgMemory(), b.peakMemory(), b.peakMemory() != null, reasons)
                    .withRecommendation(alternatives, agreement(direction, ruleCandidate, co, suggestedType),
                            applicableReason == null, applicableReason));
        }

        candidates.sort(Comparator.comparing(
                (RightsizingCandidateDTO d) -> d.estimatedMonthlySavings() == null ? BigDecimal.ZERO : d.estimatedMonthlySavings()
        ).reversed());
        return candidates;
    }

    /** A priced option: its 30-day cost, the saving against the current cost, and whether a rate was another region's. */
    private record Priced(BigDecimal monthlyCost, BigDecimal savings, boolean approximate) {
    }

    private Priced price(VmCostBundle b, String provider, int nodes, String type) {
        if (type == null || !b.estimate().costKnown()) {
            return null;
        }
        PricingReferenceService.PriceLookupResult rate = pricingReferenceService.lookupHourlyRate(provider, type, b.vm().getRegion());
        if (!rate.priceKnown()) {
            return null;
        }
        BigDecimal after = vmCostCalculator.estimateCost(rate.hourlyRate().multiply(BigDecimal.valueOf(nodes)),
                b.estimate().runtimeHours(),
                b.estimate().storageGib(), pricingReferenceService.getStorageGbMonthRate(),
                // Intentionally the full window: the "after" cost is a 30-day projection.
                BigDecimal.valueOf(COST_WINDOW_DAYS));
        return new Priced(after, b.estimate().cost().subtract(after), rate.approximate());
    }

    /**
     * The newest same-architecture newer-generation type of the same size that costs the same or
     * less per hour than the current one, the cheapest within that generation (E30-T05), else null.
     * m5.large in us-east-1 becomes m6a.large: m7i.large costs more per hour.
     */
    private String modernizeTarget(VmCostBundle b, String provider) {
        if (b.instanceType() == null) {
            return null;
        }
        PricingReferenceService.PriceLookupResult now = pricingReferenceService.lookupHourlyRate(provider, b.instanceType(), b.vm().getRegion());
        if (!now.priceKnown()) {
            return null;
        }
        String best = null;
        BigDecimal bestRate = null;
        int bestGeneration = -1;
        for (String type : pricingReferenceService.newerGenerationsOf(b.instanceType(), currentArchitecture(b))) {
            int generation = pricingReferenceService.getSpec(type).map(PricingReferenceService.InstanceSpec::generation).orElse(0);
            if (best != null && generation < bestGeneration) {
                break; // newest generation first: an older one is never preferred
            }
            PricingReferenceService.PriceLookupResult rate = pricingReferenceService.lookupHourlyRate(provider, type, b.vm().getRegion());
            if (rate.priceKnown() && rate.hourlyRate().compareTo(now.hourlyRate()) <= 0
                    && (bestRate == null || rate.hourlyRate().compareTo(bestRate) < 0)) {
                best = type;
                bestRate = rate.hourlyRate();
                bestGeneration = generation;
            }
        }
        return best;
    }

    /** x86_64 or arm64: the inventory's, else the current type's spec, else x86_64. */
    private String currentArchitecture(VmCostBundle b) {
        if (b.architecture() != null && !b.architecture().isBlank()) {
            return b.architecture().toLowerCase(java.util.Locale.ROOT).startsWith("arm") ? "arm64" : "x86_64";
        }
        return pricingReferenceService.getSpec(b.instanceType()).map(PricingReferenceService.InstanceSpec::architecture).orElse("x86_64");
    }

    private String architectureOf(String type) {
        return pricingReferenceService.getSpec(type).map(PricingReferenceService.InstanceSpec::architecture).orElse(null);
    }

    /** Why a type cannot be applied for its architecture, or null when it runs on the VM's image. */
    private String architectureChangeReason(String currentArchitecture, String type) {
        String target = architectureOf(type);
        if (target == null || target.equals(currentArchitecture)) {
            return null;
        }
        return "arm64".equals(target) ? "Requires an ARM64 image" : "Requires an x86_64 image";
    }

    /**
     * The chosen option, the newer-generation and Graviton equivalents (of the suggested size, else
     * the current one) and every Compute Optimizer option, each priced, without repeats (E30-T05).
     */
    private List<RightsizingOptionDTO> alternatives(VmCostBundle b, String provider, int nodes, boolean eks, boolean scaleDown,
                                                    String suggestedType, String chosenKind, String chosenSource,
                                                    ComputeOptimizerService.Recommendation co, String architecture) {
        Map<String, RightsizingOptionDTO> byType = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, Object[]> add = (type, meta) -> {
            if (type == null || type.equals(b.instanceType()) || byType.containsKey(type)) {
                return;
            }
            String kind = (String) meta[0];
            String source = (String) meta[1];
            BigDecimal risk = (BigDecimal) meta[2];
            String typeArchitecture = architectureOf(type);
            String archReason = architectureChangeReason(architecture, type);
            String reason = eks ? "Change the node group in EKS"
                    : archReason != null ? archReason
                    : scaleDown && !memoryFits(b, type) ? "Would not hold the memory peak"
                    : null;
            Priced priced = price(b, provider, nodes, type);
            byType.put(type, new RightsizingOptionDTO(type, kind, source, typeArchitecture,
                    priced == null ? null : priced.monthlyCost(), priced == null ? null : priced.savings(), risk,
                    archReason != null, reason == null, reason));
        };
        BigDecimal chosenRisk = co == null || suggestedType == null ? null : co.options().stream()
                .filter(o -> suggestedType.equals(o.instanceType())).map(ComputeOptimizerService.Option::performanceRisk)
                .findFirst().orElse(null);
        add.accept(suggestedType, new Object[] {"NEW_GEN".equals(chosenKind) ? "NEWER_GENERATION" : "RIGHTSIZE", chosenSource, chosenRisk});
        String base = suggestedType != null ? suggestedType : b.instanceType();
        add.accept(pricingReferenceService.suggestNewerGeneration(provider, base, architecture).orElse(null),
                new Object[] {"NEWER_GENERATION", "newer-generation", null});
        String graviton = pricingReferenceService.suggestGraviton(provider, base)
                .or(() -> pricingReferenceService.suggestGraviton(provider, b.instanceType())).orElse(null);
        if (!"arm64".equals(architecture)) {
            add.accept(graviton, new Object[] {"GRAVITON", "graviton", null});
        }
        if (co != null) {
            for (ComputeOptimizerService.Option option : co.options()) {
                String typeArchitecture = architectureOf(option.instanceType());
                boolean arm = "arm64".equals(typeArchitecture) && !"arm64".equals(architecture);
                add.accept(option.instanceType(), new Object[] {arm ? "GRAVITON" : "RIGHTSIZE", "compute-optimizer", option.performanceRisk()});
            }
        }
        return List.copyOf(byType.values());
    }

    /**
     * AGREE when Compute Optimizer's top option is the suggestion or both point the same way,
     * ONLY_RULE / ONLY_AWS when one side has nothing, DISAGREE when they point different ways
     * (E30-T05).
     */
    private static String agreement(String direction, boolean ruleCandidate, ComputeOptimizerService.Recommendation co,
                                    String suggestedType) {
        if (co == null) {
            return "ONLY_RULE";
        }
        if (!ruleCandidate) {
            return "ONLY_AWS";
        }
        if (suggestedType != null && suggestedType.equals(co.suggestedInstanceType())) {
            return "AGREE";
        }
        String finding = co.finding();
        return switch (direction) {
            case "SCALE_DOWN" -> ComputeOptimizerService.OVER_PROVISIONED.equals(finding) ? "AGREE"
                    : "OPTIMIZED".equals(finding) || "UNDER_PROVISIONED".equals(finding) ? "DISAGREE" : "ONLY_RULE";
            case "SCALE_UP" -> "UNDER_PROVISIONED".equals(finding) ? "AGREE"
                    : "OPTIMIZED".equals(finding) || ComputeOptimizerService.OVER_PROVISIONED.equals(finding) ? "DISAGREE" : "ONLY_RULE";
            default -> co.optionTypesByRank().contains(suggestedType) ? "AGREE"
                    : "UNDER_PROVISIONED".equals(finding) ? "DISAGREE" : "ONLY_RULE";
        };
    }

    /**
     * Whether {@code targetType} holds the VM's memory peak plus headroom: required = current
     * memory x peak% x (1 + headroom%). True when the check cannot be made (memory-aware rightsizing
     * off, no memory peak, or the current or target memory unknown).
     */
    private boolean memoryFits(VmCostBundle b, String targetType) {
        if (!memoryEnabled() || b.peakMemory() == null || b.currentMemoryMib() == null) {
            return true;
        }
        Optional<PricingReferenceService.InstanceSpec> target = pricingReferenceService.getSpec(targetType);
        if (target.isEmpty()) {
            return true;
        }
        BigDecimal headroom = BigDecimal.ONE.add(BigDecimal.valueOf(live("rightsizing.memory.headroom-percent", memoryHeadroomPercent))
                .movePointLeft(2));
        BigDecimal required = BigDecimal.valueOf(b.currentMemoryMib())
                .multiply(b.peakMemory().movePointLeft(2)).multiply(headroom);
        return BigDecimal.valueOf(target.get().memoryMib()).compareTo(required) >= 0;
    }

    /**
     * Compute Optimizer's stored view (E30-T04) of every instance of the fleet's plain-EC2 accounts
     * and regions, any finding — no AWS call; empty when {@code cost.optimizer.enabled=false}.
     */
    private Map<String, ComputeOptimizerService.Recommendation> fetchComputeOptimizerRecommendations(List<VmCostBundle> bundles) {
        Map<String, ComputeOptimizerService.Recommendation> result = new LinkedHashMap<>();
        bundles.stream()
                .filter(b -> b.vm().getProvider() == CloudProvider.AWS)
                .map(b -> CloudTarget.of(b.vm()))
                .distinct()
                .forEach(target -> result.putAll(computeOptimizerService.getAllRecommendations(target)));
        return result;
    }

    private IdleWasteRowDTO toIdleWasteRow(VmCostBundle b) {
        Integer nodes = b.vm().getProvider() == CloudProvider.AWS_EKS
                ? EstimatedCostProvider.eksNodeInfo(b.vm().getMetadata()).nodeCount() : null;
        return new IdleWasteRowDTO(
                b.vm().getVmId(),
                b.vm().getDisplayName(),
                b.vm().getGroup().getEnvironment().getEnvironmentId(),
                b.vm().getGroup().getEnvironment().getDisplayName(),
                b.vm().getGroup().getDisplayName(),
                b.estimate().cost(),
                b.estimate().costKnown(),
                b.idleDurationMinutes(),
                b.idleSince(),
                b.latestCpuUtilization(),
                monthlyIdleCost(b)
        ).withNodeCount(nodes);
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
                b.estimate().storageGib(),
                b.estimate().priceApproximate()
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
            BigDecimal avgMemory,
            BigDecimal peakMemory,
            boolean idle,
            Timestamp idleSince,
            Integer idleDurationMinutes,
            BigDecimal latestCpuUtilization,
            boolean scaleDownCandidate,
            boolean scaleUpCandidate,
            Timestamp instanceTypeChangedAt,
            /** Memory of the current type: inventory, else the pricing reference's spec (E30-T03). */
            Integer currentMemoryMib,
            /** Reason codes of the direction the VM qualifies for (E30-T03). */
            List<String> reasons,
            /** x86_64 or arm64 from inventory (E30-T05); null when not recorded. */
            String architecture
    ) {}
}
