package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EnvironmentInsightsDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class EnvironmentInsightsService {

    private final EnvironmentService environmentService;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final VmVolumeSnapshotRepository volumeRepository;
    private final VmMetricSampleRepository sampleRepository;
    private final VmIdleSummaryRepository idleSummaryRepository;

    public EnvironmentInsightsService(EnvironmentService environmentService,
                                      VmGroupRepository groupRepository,
                                      VmRepository vmRepository,
                                      VmInventorySnapshotRepository inventoryRepository,
                                      VmVolumeSnapshotRepository volumeRepository,
                                      VmMetricSampleRepository sampleRepository,
                                      VmIdleSummaryRepository idleSummaryRepository) {
        this.environmentService = environmentService;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.volumeRepository = volumeRepository;
        this.sampleRepository = sampleRepository;
        this.idleSummaryRepository = idleSummaryRepository;
    }

    // Optional collaborators for the actions (U08-T08); insights work without them.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private CostDataProvider costDataProvider;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private CostEstimationService costEstimationService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private AutomationRuleService automationRuleService;

    /** The windows insights accept (U08-T08). */
    public static java.time.Duration parseWindow(String window) {
        if (window == null || window.isBlank() || "24h".equalsIgnoreCase(window)) {
            return java.time.Duration.ofHours(24);
        }
        if ("7d".equalsIgnoreCase(window)) {
            return java.time.Duration.ofDays(7);
        }
        throw new IllegalArgumentException("window must be 24h or 7d");
    }

    @Transactional(readOnly = true)
    public EnvironmentInsightsDTO getInsights(String environmentId) {
        return getInsights(environmentId, null);
    }

    @Transactional(readOnly = true)
    public EnvironmentInsightsDTO getInsights(String environmentId, java.util.Collection<String> visibleGroupIds) {
        return getInsights(environmentId, visibleGroupIds, "24h");
    }

    /**
     * @param visibleGroupIds when non-null, groups (and their VMs) are restricted to this set —
     *                        so a group-scoped viewer only sees insights for the groups they
     *                        can access. Null means every group.
     */
    @Transactional(readOnly = true)
    public EnvironmentInsightsDTO getInsights(String environmentId, java.util.Collection<String> visibleGroupIds,
                                              String window) {
        java.time.Duration windowLength = parseWindow(window);
        String windowKey = windowLength.toDays() >= 7 ? "7d" : "24h";
        Environment environment = environmentService.getEnvironmentById(environmentId);
        List<VmGroup> groups = groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environmentId);
        List<Vm> vms = vmRepository.findByEnvironmentIdFetchGroupAndEnvironment(environmentId);

        if (visibleGroupIds != null) {
            java.util.Set<String> visible = new java.util.HashSet<>(visibleGroupIds);
            groups = groups.stream().filter(g -> visible.contains(g.getGroupId())).toList();
            vms = vms.stream().filter(vm -> vm.getGroup() != null && visible.contains(vm.getGroup().getGroupId())).toList();
        }

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> regionCounts = new LinkedHashMap<>();
        Map<String, Integer> volumeTypeCounts = new LinkedHashMap<>();
        List<EnvironmentInsightsDTO.VmInsightRowDTO> vmRows = new ArrayList<>();

        int runningVms = 0;
        int stoppedVms = 0;
        int transitionalVms = 0;
        int unknownVms = 0;
        int driftedVms = 0;
        int idleVms = 0;
        int missingInventoryVms = 0;
        int missingMetricVms = 0;
        int totalAllocatedStorageGib = 0;
        int volumeCount = 0;
        long totalNetworkInBytes = 0;
        long totalNetworkOutBytes = 0;
        long totalDiskReadBytes = 0;
        long totalDiskWriteBytes = 0;
        BigDecimal cpuTotal = BigDecimal.ZERO;
        int cpuSampleCount = 0;
        Timestamp latestMetricSampleTime = null;
        Timestamp latestInventoryRefreshTime = null;
        Instant seriesStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant seriesEnd = Instant.now();
        List<VmSeriesSamples> vmSeriesSamples = new ArrayList<>();

        // Everything per VM is loaded up front in a few IN queries (M36): this was five queries
        // per VM, about 1,000 for a 200-VM environment.
        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();
        Map<String, VmInventorySnapshot> inventoryByVm = new HashMap<>();
        Map<String, List<VmVolumeSnapshot>> volumesByVm = new HashMap<>();
        Map<String, VmIdleSummary> idleByVm = new HashMap<>();
        Map<String, VmMetricSample> latestByVm = new HashMap<>();
        Map<String, List<VmMetricSample>> seriesByVm = new HashMap<>();
        Map<String, BigDecimal> windowCpuByVm = new HashMap<>();
        Timestamp windowStart = Timestamp.from(seriesEnd.minus(windowLength));
        for (List<String> chunk : chunks(vmIds)) {
            sampleRepository.avgCpuByVmIdsSince(chunk, windowStart).forEach(a -> {
                if (a.getAvgCpu() != null) {
                    windowCpuByVm.put(a.getVmId(), BigDecimal.valueOf(a.getAvgCpu().doubleValue()).setScale(2, RoundingMode.HALF_UP));
                }
            });
            inventoryRepository.findByVmVmIdIn(chunk).forEach(i -> inventoryByVm.put(i.getVm().getVmId(), i));
            volumeRepository.findByVmVmIdIn(chunk).forEach(v ->
                    volumesByVm.computeIfAbsent(v.getVm().getVmId(), k -> new ArrayList<>()).add(v));
            idleSummaryRepository.findByVmVmIdIn(chunk).forEach(i -> idleByVm.put(i.getVm().getVmId(), i));
            sampleRepository.findLatestByVmIds(chunk).forEach(s ->
                    latestByVm.merge(s.getVm().getVmId(), s, (a, b) -> a.getSampleTime().compareTo(b.getSampleTime()) >= 0 ? a : b));
            sampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(
                            chunk, Timestamp.from(seriesStart), Timestamp.from(seriesEnd))
                    .forEach(s -> seriesByVm.computeIfAbsent(s.getVm().getVmId(), k -> new ArrayList<>()).add(s));
        }
        volumesByVm.values().forEach(list -> list.sort(Comparator.comparing(VmVolumeSnapshot::getDeviceName,
                Comparator.nullsLast(Comparator.naturalOrder()))));

        for (Vm vm : vms) {
            VmStatus status = vm.getStatus();
            statusCounts.merge(status == null ? "UNKNOWN" : status.name(), 1, Integer::sum);
            regionCounts.merge(emptyToUnknown(vm.getRegion()), 1, Integer::sum);

            if (status == VmStatus.RUNNING) runningVms++;
            else if (status == VmStatus.STOPPED) stoppedVms++;
            else if (status == VmStatus.STARTING || status == VmStatus.STOPPING) transitionalVms++;
            else unknownVms++;

            if (Boolean.TRUE.equals(vm.getStateDriftDetected())) {
                driftedVms++;
            }

            Optional<VmInventorySnapshot> inventory = Optional.ofNullable(inventoryByVm.get(vm.getVmId()));
            if (inventory.isPresent()) {
                VmInventorySnapshot snapshot = inventory.get();
                latestInventoryRefreshTime = maxTimestamp(latestInventoryRefreshTime, snapshot.getLastRefreshedAt());
            } else {
                missingInventoryVms++;
            }

            List<VmVolumeSnapshot> volumes = volumesByVm.getOrDefault(vm.getVmId(), List.of());
            int vmStorageGib = 0;
            for (VmVolumeSnapshot volume : volumes) {
                volumeCount++;
                vmStorageGib += nullToZero(volume.getSizeGib());
                volumeTypeCounts.merge(emptyToUnknown(volume.getVolumeType()), 1, Integer::sum);
            }
            totalAllocatedStorageGib += vmStorageGib;

            Optional<VmMetricSample> latestSample = Optional.ofNullable(latestByVm.get(vm.getVmId()));
            Optional<VmIdleSummary> idleSummary = Optional.ofNullable(idleByVm.get(vm.getVmId()));
            List<VmMetricSample> recentSamples = seriesByVm.getOrDefault(vm.getVmId(), List.of());
            if (!recentSamples.isEmpty()) {
                vmSeriesSamples.add(new VmSeriesSamples(
                        vm.getVmId(),
                        vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName(),
                        recentSamples
                ));
            }

            if (latestSample.isPresent()) {
                VmMetricSample sample = latestSample.get();
                latestMetricSampleTime = maxTimestamp(latestMetricSampleTime, sample.getSampleTime());
                if (sample.getCpuUtilization() != null) {
                    cpuTotal = cpuTotal.add(sample.getCpuUtilization());
                    cpuSampleCount++;
                }
                totalNetworkInBytes += nullToZero(sample.getNetworkInBytes());
                totalNetworkOutBytes += nullToZero(sample.getNetworkOutBytes());
                totalDiskReadBytes += nullToZero(sample.getDiskReadBytes());
                totalDiskWriteBytes += nullToZero(sample.getDiskWriteBytes());
            } else {
                missingMetricVms++;
            }

            // Only a running VM is idle now; a stopped one may keep an old summary (U23-T03).
            boolean idle = status == VmStatus.RUNNING && idleSummary.map(VmIdleSummary::getIdle).orElse(false);
            if (idle) {
                idleVms++;
            }

            vmRows.add(new EnvironmentInsightsDTO.VmInsightRowDTO(
                    vm.getVmId(),
                    vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName(),
                    vm.getGroup().getGroupId(),
                    vm.getGroup().getDisplayName() != null ? vm.getGroup().getDisplayName() : vm.getGroup().getName(),
                    status == null ? "UNKNOWN" : status.name(),
                    latestSample.map(VmMetricSample::getCpuUtilization).orElse(null),
                    idle ? idleSummary.map(VmIdleSummary::getIdleDurationMinutes).orElse(0) : 0,
                    vmStorageGib,
                    latestSample.map(VmMetricSample::getSampleTime).orElse(null),
                    windowCpuByVm.get(vm.getVmId())
            ));
        }

        // Keyed by group id: two groups may share a display name (they were merged).
        Map<String, List<EnvironmentInsightsDTO.VmInsightRowDTO>> rowsByGroupId = vmRows.stream()
                .collect(Collectors.groupingBy(EnvironmentInsightsDTO.VmInsightRowDTO::groupId));

        List<EnvironmentInsightsDTO.GroupInsightDTO> groupInsights = groups.stream()
                .map(group -> buildGroupInsight(group, rowsByGroupId.getOrDefault(group.getGroupId(), List.of())))
                .toList();

        BigDecimal avgCpu = cpuSampleCount == 0
                ? null
                : cpuTotal.divide(BigDecimal.valueOf(cpuSampleCount), 2, RoundingMode.HALF_UP);

        int activeVms = Math.max(0, vms.size() - idleVms);
        // Busiest over the window (U08-T08); the latest sample only when the window has none.
        java.util.function.Function<EnvironmentInsightsDTO.VmInsightRowDTO, BigDecimal> busy =
                row -> row.avgCpuWindow() != null ? row.avgCpuWindow() : row.cpuUtilization();
        List<EnvironmentInsightsDTO.VmInsightRowDTO> busiestVms = vmRows.stream()
                .filter(row -> busy.apply(row) != null)
                .sorted(Comparator.comparing(busy, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(5)
                .toList();
        List<EnvironmentInsightsDTO.VmInsightRowDTO> idleRows = vmRows.stream()
                .filter(row -> row.idleDurationMinutes() != null && row.idleDurationMinutes() > 0)
                .sorted(Comparator.comparing(EnvironmentInsightsDTO.VmInsightRowDTO::idleDurationMinutes).reversed())
                .limit(5)
                .toList();

        return new EnvironmentInsightsDTO(
                environment.getEnvironmentId(),
                environment.getName(),
                environment.getDisplayName(),
                environment.getServiceType(),
                groups.size(),
                vms.size(),
                runningVms,
                stoppedVms,
                transitionalVms,
                unknownVms,
                driftedVms,
                idleVms,
                activeVms,
                missingInventoryVms,
                missingMetricVms,
                totalAllocatedStorageGib,
                volumeCount,
                avgCpu,
                totalNetworkInBytes,
                totalNetworkOutBytes,
                totalDiskReadBytes,
                totalDiskWriteBytes,
                buildCpuSeries(vmSeriesSamples),
                buildByteSeries(vmSeriesSamples, MetricValueExtractor.NETWORK_IN),
                buildByteSeries(vmSeriesSamples, MetricValueExtractor.NETWORK_OUT),
                buildByteSeries(vmSeriesSamples, MetricValueExtractor.DISK_READ),
                buildByteSeries(vmSeriesSamples, MetricValueExtractor.DISK_WRITE),
                latestMetricSampleTime,
                latestInventoryRefreshTime,
                statusCounts,
                regionCounts,
                volumeTypeCounts,
                groupInsights,
                busiestVms,
                idleRows,
                buildRecommendations(idleVms, missingInventoryVms, missingMetricVms, driftedVms, totalAllocatedStorageGib),
                windowKey,
                buildActions(environmentId, vms, idleByVm, seriesEnd)
        );
    }

    /** How many actions insights returns at most; the panel shows the first three. */
    static final int MAX_ACTIONS = 5;
    /** Weekend idle hours across the environment's VMs that are worth a weekend stop. */
    static final int WEEKEND_IDLE_HOURS = 16;

    /**
     * What to do (U08-T08): stop running VMs that idle now (with what they cost an hour), resize the
     * ones the rightsizing rules flag (with the monthly saving), and stop on weekends too when the
     * environment has a schedule and its VMs idled through last weekend.
     */
    List<EnvironmentInsightsDTO.ActionDTO> buildActions(String environmentId, List<Vm> vms,
                                                        Map<String, VmIdleSummary> idleByVm, Instant now) {
        List<EnvironmentInsightsDTO.ActionDTO> actions = new ArrayList<>();
        if (vms.isEmpty()) {
            return actions;
        }
        Map<String, Vm> byId = new LinkedHashMap<>();
        vms.forEach(vm -> byId.put(vm.getVmId(), vm));

        List<Vm> idleRunning = vms.stream()
                .filter(vm -> vm.getStatus() == VmStatus.RUNNING)
                .filter(vm -> idleByVm.get(vm.getVmId()) != null && Boolean.TRUE.equals(idleByVm.get(vm.getVmId()).getIdle()))
                .sorted(Comparator.comparing((Vm vm) -> nullToZero(idleByVm.get(vm.getVmId()).getIdleDurationMinutes())).reversed())
                .toList();
        Map<String, CostDataProvider.VmCostEstimate> rates = idleRunning.isEmpty() || costDataProvider == null ? Map.of()
                : costDataProvider.estimateCosts(idleRunning, Timestamp.from(now.minus(1, ChronoUnit.HOURS)), Timestamp.from(now));
        for (Vm vm : idleRunning) {
            CostDataProvider.VmCostEstimate e = rates.get(vm.getVmId());
            int minutes = nullToZero(idleByVm.get(vm.getVmId()).getIdleDurationMinutes());
            actions.add(new EnvironmentInsightsDTO.ActionDTO("STOP_IDLE", vm.getVmId(), name(vm), groupName(vm), null,
                    e != null && e.costKnown() ? e.hourlyRate() : null, null, "Idle for " + idleText(minutes)));
        }

        if (costEstimationService != null) {
            costEstimationService.findCandidates(byId.keySet()).values().stream()
                    .filter(c -> c.suggestedInstanceType() != null && !c.suggestedInstanceType().equals(c.currentInstanceType()))
                    .sorted(Comparator.comparing((com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO c) ->
                            c.estimatedMonthlySavings() == null ? BigDecimal.ZERO : c.estimatedMonthlySavings()).reversed())
                    .forEach(c -> {
                        Vm vm = byId.get(c.vmId());
                        actions.add(new EnvironmentInsightsDTO.ActionDTO("RESIZE", c.vmId(), vm == null ? c.vmName() : name(vm),
                                vm == null ? null : groupName(vm), c.suggestedInstanceType(), null,
                                c.estimatedMonthlySavings() == null ? null : c.estimatedMonthlySavings().setScale(2, RoundingMode.HALF_UP),
                                "CPU peaked at " + (c.peakCpuUtilization() == null ? "—"
                                        : c.peakCpuUtilization().setScale(0, RoundingMode.HALF_UP).toPlainString() + "%")));
                    });
        }

        if (automationRuleService != null && automationRuleService.nextScheduledFirings(environmentId, now).ruleCount() > 0) {
            List<VmMetricSampleRepository.VmIdleSeconds> weekend = new ArrayList<>();
            for (List<String> chunk : chunks(new ArrayList<>(byId.keySet()))) {
                weekend.addAll(sampleRepository.weekendIdleSecondsByVmIds(chunk,
                        Timestamp.from(now.minus(7, ChronoUnit.DAYS)), BigDecimal.valueOf(5)));
            }
            long seconds = weekend.stream().mapToLong(w -> w.getSeconds() == null ? 0 : w.getSeconds().longValue()).sum();
            long hours = seconds / 3600;
            long vmsIdle = weekend.stream().filter(w -> w.getSeconds() != null && w.getSeconds().longValue() >= 3600).count();
            if (hours >= WEEKEND_IDLE_HOURS && vmsIdle > 0) {
                actions.add(new EnvironmentInsightsDTO.ActionDTO("SCHEDULE_WEEKENDS", null, null, null, null, null, null,
                        vmsIdle + " VM" + (vmsIdle == 1 ? "" : "s") + " ran idle for about " + hours + " h over the weekend"));
            }
        }
        return actions.size() > MAX_ACTIONS ? actions.subList(0, MAX_ACTIONS) : actions;
    }

    private static String name(Vm vm) {
        return vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName();
    }

    private static String groupName(Vm vm) {
        if (vm.getGroup() == null) return null;
        return vm.getGroup().getDisplayName() != null ? vm.getGroup().getDisplayName() : vm.getGroup().getName();
    }

    /** "45 min", "3 h", "2 days". */
    static String idleText(int minutes) {
        if (minutes < 60) return minutes + " min";
        if (minutes < 48 * 60) return (minutes / 60) + " h";
        return (minutes / 1440) + " days";
    }

    void setCollaborators(CostDataProvider costs, CostEstimationService estimation, AutomationRuleService rules) {
        this.costDataProvider = costs;
        this.costEstimationService = estimation;
        this.automationRuleService = rules;
    }

    private static final int IN_CHUNK = 500;

    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            chunks.add(ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
        return chunks; // empty for no VMs: no IN () query is issued
    }

    private EnvironmentInsightsDTO.GroupInsightDTO buildGroupInsight(
            VmGroup group,
            List<EnvironmentInsightsDTO.VmInsightRowDTO> rows) {
        int running = (int) rows.stream().filter(row -> Objects.equals(row.status(), "RUNNING")).count();
        int stopped = (int) rows.stream().filter(row -> Objects.equals(row.status(), "STOPPED")).count();
        int idle = (int) rows.stream().filter(row -> row.idleDurationMinutes() != null && row.idleDurationMinutes() > 0).count();
        List<BigDecimal> cpus = rows.stream()
                .map(row -> row.avgCpuWindow() != null ? row.avgCpuWindow() : row.cpuUtilization())
                .filter(Objects::nonNull)
                .toList();
        BigDecimal avgCpu = cpus.isEmpty()
                ? null
                : cpus.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(cpus.size()), 2, RoundingMode.HALF_UP);

        return new EnvironmentInsightsDTO.GroupInsightDTO(
                group.getGroupId(),
                group.getDisplayName() != null ? group.getDisplayName() : group.getName(),
                group.getSequencePosition(),
                rows.size(),
                running,
                stopped,
                idle,
                avgCpu
        );
    }

    private List<EnvironmentInsightsDTO.RecommendationDTO> buildRecommendations(
            int idleVms,
            int missingInventoryVms,
            int missingMetricVms,
            int driftedVms,
            int totalAllocatedStorageGib) {
        List<EnvironmentInsightsDTO.RecommendationDTO> recommendations = new ArrayList<>();
        if (idleVms > 0) {
            recommendations.add(new EnvironmentInsightsDTO.RecommendationDTO(
                    "warning", "Review idle VMs", "VMs have stayed below configured activity thresholds.", idleVms));
        }
        if (missingInventoryVms > 0) {
            recommendations.add(new EnvironmentInsightsDTO.RecommendationDTO(
                    "muted", "Refresh inventory", "Some VMs do not have an inventory snapshot yet.", missingInventoryVms));
        }
        if (missingMetricVms > 0) {
            recommendations.add(new EnvironmentInsightsDTO.RecommendationDTO(
                    "muted", "Wait for metrics", "Some VMs do not have CloudWatch samples collected yet.", missingMetricVms));
        }
        if (driftedVms > 0) {
            recommendations.add(new EnvironmentInsightsDTO.RecommendationDTO(
                    "danger", "Resolve state drift", "Cloud state differs from the stored VM state.", driftedVms));
        }
        if (totalAllocatedStorageGib > 0) {
            recommendations.add(new EnvironmentInsightsDTO.RecommendationDTO(
                    "primary", "Track allocated EBS", "Allocated storage is visible; filesystem used/free needs an in-guest collector.", totalAllocatedStorageGib));
        }
        return recommendations;
    }

    private String emptyToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long nullToZero(Long value) {
        return value == null ? 0 : value;
    }

    private Timestamp maxTimestamp(Timestamp left, Timestamp right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.after(left) ? right : left;
    }

    private List<EnvironmentInsightsDTO.VmMetricSeriesDTO> buildCpuSeries(List<VmSeriesSamples> vmSeriesSamples) {
        return vmSeriesSamples.stream()
                .map(vmSeries -> {
                    List<Integer> values = vmSeries.samples().stream()
                            .map(VmMetricSample::getCpuUtilization)
                            .filter(Objects::nonNull)
                            .map(value -> value.setScale(0, RoundingMode.HALF_UP).intValue())
                            .toList();
                    return new EnvironmentInsightsDTO.VmMetricSeriesDTO(
                            vmSeries.vmId(), vmSeries.name(), tail(values, 12));
                })
                .filter(series -> series.values().size() > 1)
                .sorted(Comparator.comparingInt((EnvironmentInsightsDTO.VmMetricSeriesDTO series) ->
                        series.values().stream().mapToInt(Integer::intValue).max().orElse(0)).reversed())
                .limit(5)
                .toList();
    }

    private List<EnvironmentInsightsDTO.VmMetricSeriesDTO> buildByteSeries(
            List<VmSeriesSamples> vmSeriesSamples,
            MetricValueExtractor extractor) {
        return vmSeriesSamples.stream()
                .map(vmSeries -> {
                    List<Integer> values = vmSeries.samples().stream()
                            .map(extractor::value)
                            .filter(Objects::nonNull)
                            .map(bytes -> (int) Math.max(0, Math.round(bytes / (1024.0 * 1024.0))))
                            .toList();
                    return new EnvironmentInsightsDTO.VmMetricSeriesDTO(
                            vmSeries.vmId(), vmSeries.name(), tail(values, 12));
                })
                .filter(series -> series.values().size() > 1)
                .sorted(Comparator.comparingInt((EnvironmentInsightsDTO.VmMetricSeriesDTO series) ->
                        series.values().stream().mapToInt(Integer::intValue).max().orElse(0)).reversed())
                .limit(5)
                .toList();
    }

    private List<Integer> tail(List<Integer> values, int count) {
        return values.stream().skip(Math.max(0, values.size() - count)).toList();
    }

    private record VmSeriesSamples(String vmId, String name, List<VmMetricSample> samples) {}

    private enum MetricValueExtractor {
        NETWORK_IN {
            @Override Long value(VmMetricSample sample) { return sample.getNetworkInBytes(); }
        },
        NETWORK_OUT {
            @Override Long value(VmMetricSample sample) { return sample.getNetworkOutBytes(); }
        },
        DISK_READ {
            @Override Long value(VmMetricSample sample) { return sample.getDiskReadBytes(); }
        },
        DISK_WRITE {
            @Override Long value(VmMetricSample sample) { return sample.getDiskWriteBytes(); }
        };

        abstract Long value(VmMetricSample sample);
    }
}
