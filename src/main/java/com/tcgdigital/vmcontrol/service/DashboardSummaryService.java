package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DashboardSummaryService {

    private static final int TREND_BUCKETS = 7;

    private final EnvironmentService environmentService;
    private final UserService userService;
    private final VmRepository vmRepository;
    private final VmMetricSampleRepository metricSampleRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final VmIdleSummaryRepository idleSummaryRepository;
    private final VmVolumeSnapshotRepository volumeRepository;
    private final EnvironmentLockRepository lockRepository;
    private final ScheduledJobLockRepository scheduledJobLockRepository;
    private final EnvironmentAccessRequestRepository accessRequestRepository;

    public DashboardSummaryService(EnvironmentService environmentService,
                                   UserService userService,
                                   VmRepository vmRepository,
                                   VmMetricSampleRepository metricSampleRepository,
                                   VmInventorySnapshotRepository inventoryRepository,
                                   VmIdleSummaryRepository idleSummaryRepository,
                                   VmVolumeSnapshotRepository volumeRepository,
                                   EnvironmentLockRepository lockRepository,
                                   ScheduledJobLockRepository scheduledJobLockRepository,
                                   EnvironmentAccessRequestRepository accessRequestRepository) {
        this.environmentService = environmentService;
        this.userService = userService;
        this.vmRepository = vmRepository;
        this.metricSampleRepository = metricSampleRepository;
        this.inventoryRepository = inventoryRepository;
        this.idleSummaryRepository = idleSummaryRepository;
        this.volumeRepository = volumeRepository;
        this.lockRepository = lockRepository;
        this.scheduledJobLockRepository = scheduledJobLockRepository;
        this.accessRequestRepository = accessRequestRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDTO getSummary() {
        boolean admin = userService.isCurrentUserAdmin();
        List<Environment> environments = admin
                ? environmentService.getAllActiveEnvironments()
                : environmentService.getEnvironmentsForCurrentUser();
        return buildSummary(admin ? "ADMIN" : "USER", environments);
    }

    private DashboardSummaryDTO buildSummary(String persona, List<Environment> environments) {
        List<String> environmentIds = environments.stream()
                .map(Environment::getEnvironmentId)
                .toList();

        List<Vm> vms = environmentIds.stream()
                .flatMap(environmentId -> vmRepository.findByEnvironmentId(environmentId).stream())
                .toList();
        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();
        Map<String, Environment> environmentById = environments.stream()
                .collect(Collectors.toMap(Environment::getEnvironmentId, Function.identity()));
        Map<String, List<Vm>> vmsByEnvironmentId = vms.stream()
                .collect(Collectors.groupingBy(vm -> vm.getGroup().getEnvironment().getEnvironmentId()));

        List<VmIdleSummary> idleSummaries = vmIds.isEmpty() ? List.of() : idleSummaryRepository.findByVmVmIdIn(vmIds);
        Map<String, VmIdleSummary> idleByVmId = idleSummaries.stream()
                .collect(Collectors.toMap(summary -> summary.getVm().getVmId(), Function.identity()));

        List<VmInventorySnapshot> inventories = vmIds.isEmpty() ? List.of() : inventoryRepository.findByVmVmIdIn(vmIds);
        Map<String, VmInventorySnapshot> inventoryByVmId = inventories.stream()
                .collect(Collectors.toMap(snapshot -> snapshot.getVm().getVmId(), Function.identity()));

        List<VmVolumeSnapshot> volumes = vmIds.isEmpty() ? List.of() : volumeRepository.findByVmVmIdIn(vmIds);
        Timestamp start = Timestamp.from(Instant.now().minus(24, ChronoUnit.HOURS));
        Timestamp end = Timestamp.from(Instant.now());
        List<VmMetricSample> samples = vmIds.isEmpty()
                ? List.of()
                : metricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(vmIds, start, end);

        int totalVms = vms.size();
        int runningVms = countStatus(vms, VmStatus.RUNNING);
        int stoppedVms = countStatus(vms, VmStatus.STOPPED);
        int idleVms = (int) idleSummaries.stream().filter(summary -> Boolean.TRUE.equals(summary.getIdle())).count();
        int driftedVms = (int) vms.stream().filter(vm -> Boolean.TRUE.equals(vm.getStateDriftDetected())).count();
        Set<String> lockedEnvironmentIds = lockRepository.findByIsActiveTrue().stream()
                .map(lock -> lock.getEnvironment().getEnvironmentId())
                .filter(environmentIds::contains)
                .collect(Collectors.toSet());

        int pendingAccessRequests = "ADMIN".equals(persona)
                ? Math.toIntExact(accessRequestRepository.countByStatus(AccessRequestStatus.PENDING))
                : 0;

        DashboardSummaryDTO.SummaryDTO summary = new DashboardSummaryDTO.SummaryDTO(
                environments.size(),
                totalVms,
                runningVms,
                stoppedVms,
                idleVms,
                driftedVms,
                lockedEnvironmentIds.size(),
                pendingAccessRequests
        );

        DashboardSummaryDTO.UtilizationDTO utilization = new DashboardSummaryDTO.UtilizationDTO(
                averageCpu(samples),
                sumLong(samples, VmMetricSample::getNetworkInBytes),
                sumLong(samples, VmMetricSample::getNetworkOutBytes),
                sumLong(samples, VmMetricSample::getDiskReadBytes),
                sumLong(samples, VmMetricSample::getDiskWriteBytes),
                samples.stream().map(VmMetricSample::getSampleTime).max(Timestamp::compareTo).orElse(null)
        );

        int totalStorage = inventories.stream()
                .map(VmInventorySnapshot::getTotalStorageGib)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        DashboardSummaryDTO.StorageDTO storage = new DashboardSummaryDTO.StorageDTO(
                totalStorage,
                volumes.size(),
                null,
                null
        );

        DashboardSummaryDTO.CoverageDTO coverage = new DashboardSummaryDTO.CoverageDTO(
                percent(inventories.size(), totalVms),
                percent(idleSummaries.stream().filter(s -> s.getLatestSampleTime() != null).count(), totalVms),
                percent(idleSummaries.size(), totalVms)
        );

        List<DashboardSummaryDTO.EnvironmentCardDTO> environmentCards = environments.stream()
                .map(env -> buildEnvironmentCard(env, vmsByEnvironmentId.getOrDefault(env.getEnvironmentId(), List.of()),
                        idleByVmId, inventoryByVmId, lockedEnvironmentIds))
                .sorted(Comparator.comparing(DashboardSummaryDTO.EnvironmentCardDTO::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new DashboardSummaryDTO(
                persona,
                summary,
                utilization,
                storage,
                coverage,
                environmentCards,
                buildTrend(samples),
                buildStatusCounts(vms),
                buildRegionCounts(vms),
                buildProviderCounts(vms),
                buildVolumeTypeCounts(volumes),
                buildTopBusyVms(vms, idleByVmId),
                buildIdleVms(vms, idleByVmId, inventoryByVmId),
                buildSchedulerHealth(vms),
                buildRiskCompliance(totalVms, driftedVms, idleVms, coverage, volumes),
                buildRecommendations(totalVms, driftedVms, idleVms, coverage, pendingAccessRequests),
                new Timestamp(System.currentTimeMillis())
        );
    }

    private DashboardSummaryDTO.EnvironmentCardDTO buildEnvironmentCard(
            Environment env,
            List<Vm> vms,
            Map<String, VmIdleSummary> idleByVmId,
            Map<String, VmInventorySnapshot> inventoryByVmId,
            Set<String> lockedEnvironmentIds) {
        int total = vms.size();
        int running = countStatus(vms, VmStatus.RUNNING);
        int stopped = countStatus(vms, VmStatus.STOPPED);
        int idle = (int) vms.stream()
                .map(vm -> idleByVmId.get(vm.getVmId()))
                .filter(Objects::nonNull)
                .filter(summary -> Boolean.TRUE.equals(summary.getIdle()))
                .count();
        BigDecimal cpu = average(vms.stream()
                .map(vm -> idleByVmId.get(vm.getVmId()))
                .filter(Objects::nonNull)
                .map(VmIdleSummary::getLatestCpuUtilization)
                .filter(Objects::nonNull)
                .toList());
        int storage = vms.stream()
                .map(vm -> inventoryByVmId.get(vm.getVmId()))
                .filter(Objects::nonNull)
                .map(VmInventorySnapshot::getTotalStorageGib)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return new DashboardSummaryDTO.EnvironmentCardDTO(
                env.getEnvironmentId(),
                env.getName(),
                env.getDisplayName(),
                env.getServiceType(),
                total,
                running,
                stopped,
                idle,
                cpu,
                storage,
                lockedEnvironmentIds.contains(env.getEnvironmentId())
        );
    }

    private int countStatus(List<Vm> vms, VmStatus status) {
        return (int) vms.stream().filter(vm -> vm.getStatus() == status).count();
    }

    private BigDecimal averageCpu(List<VmMetricSample> samples) {
        return average(samples.stream()
                .map(VmMetricSample::getCpuUtilization)
                .filter(Objects::nonNull)
                .toList());
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private long sumLong(List<VmMetricSample> samples, Function<VmMetricSample, Long> mapper) {
        return samples.stream()
                .map(mapper)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    private int percent(long value, int total) {
        if (total <= 0) return 0;
        return (int) Math.round((value * 100.0) / total);
    }

    private Map<String, Integer> buildStatusCounts(List<Vm> vms) {
        return vms.stream().collect(Collectors.groupingBy(
                vm -> vm.getStatus() == null ? "UNKNOWN" : vm.getStatus().name(),
                TreeMap::new,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
        ));
    }

    private Map<String, Integer> buildRegionCounts(List<Vm> vms) {
        return vms.stream().collect(Collectors.groupingBy(
                vm -> blankToUnknown(vm.getRegion()),
                TreeMap::new,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
        ));
    }

    private Map<String, Integer> buildProviderCounts(List<Vm> vms) {
        return vms.stream().collect(Collectors.groupingBy(
                vm -> vm.getProvider() == null ? "UNKNOWN" : vm.getProvider().name(),
                TreeMap::new,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
        ));
    }

    private Map<String, Integer> buildVolumeTypeCounts(List<VmVolumeSnapshot> volumes) {
        return volumes.stream().collect(Collectors.groupingBy(
                volume -> blankToUnknown(volume.getVolumeType()),
                TreeMap::new,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
        ));
    }

    private List<DashboardSummaryDTO.ChartPointDTO> buildTrend(List<VmMetricSample> samples) {
        if (samples.isEmpty()) return List.of();
        List<List<VmMetricSample>> buckets = new ArrayList<>();
        for (int i = 0; i < TREND_BUCKETS; i++) buckets.add(new ArrayList<>());
        Timestamp minTime = samples.stream().map(VmMetricSample::getSampleTime).min(Timestamp::compareTo).orElse(null);
        Timestamp maxTime = samples.stream().map(VmMetricSample::getSampleTime).max(Timestamp::compareTo).orElse(null);
        long min = minTime == null ? 0 : minTime.getTime();
        long max = maxTime == null ? min : maxTime.getTime();
        long span = Math.max(1, max - min);
        for (VmMetricSample sample : samples) {
            int index = (int) Math.min(TREND_BUCKETS - 1, ((sample.getSampleTime().getTime() - min) * TREND_BUCKETS) / span);
            buckets.get(index).add(sample);
        }
        List<DashboardSummaryDTO.ChartPointDTO> points = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            List<VmMetricSample> bucket = buckets.get(i);
            points.add(new DashboardSummaryDTO.ChartPointDTO(
                    "T" + (i + 1),
                    averageCpu(bucket),
                    sumLong(bucket, VmMetricSample::getNetworkInBytes),
                    sumLong(bucket, VmMetricSample::getNetworkOutBytes),
                    sumLong(bucket, VmMetricSample::getDiskReadBytes),
                    sumLong(bucket, VmMetricSample::getDiskWriteBytes)
            ));
        }
        return points;
    }

    private List<DashboardSummaryDTO.TopVmDTO> buildTopBusyVms(List<Vm> vms, Map<String, VmIdleSummary> idleByVmId) {
        return vms.stream()
                .map(vm -> toTopVm(vm, idleByVmId.get(vm.getVmId()), null))
                .filter(row -> row.cpuUtilization() != null)
                .sorted(Comparator.comparing(DashboardSummaryDTO.TopVmDTO::cpuUtilization).reversed())
                .limit(6)
                .toList();
    }

    private List<DashboardSummaryDTO.TopVmDTO> buildIdleVms(
            List<Vm> vms,
            Map<String, VmIdleSummary> idleByVmId,
            Map<String, VmInventorySnapshot> inventoryByVmId) {
        return vms.stream()
                .map(vm -> toTopVm(vm, idleByVmId.get(vm.getVmId()), inventoryByVmId.get(vm.getVmId())))
                .filter(row -> row.idleDurationMinutes() != null && row.idleDurationMinutes() > 0)
                .sorted(Comparator.comparing(DashboardSummaryDTO.TopVmDTO::idleDurationMinutes).reversed())
                .toList();
    }

    private List<DashboardSummaryDTO.SchedulerHealthDTO> buildSchedulerHealth(List<Vm> vms) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, ScheduledJobLock> locksByName = scheduledJobLockRepository.findAll().stream()
                .collect(Collectors.toMap(ScheduledJobLock::getLockName, Function.identity(), (left, right) -> left));
        Timestamp latestStateSync = vms.stream()
                .map(Vm::getLastStateSyncAt)
                .filter(Objects::nonNull)
                .max(Timestamp::compareTo)
                .orElse(null);

        return List.of(
                stateSyncHealth("State sync", latestStateSync, now),
                lockHealth("Metrics sync", locksByName.get("vm_metrics_sync"), now),
                lockHealth("Inventory sync", locksByName.get("vm_inventory_sync"), now),
                lockHealth("Metrics archive", locksByName.get("vm_metrics_archive"), now)
        );
    }

    private DashboardSummaryDTO.SchedulerHealthDTO stateSyncHealth(String name, Timestamp lastRunAt, Timestamp now) {
        Long freshness = freshnessSeconds(lastRunAt, now);
        return new DashboardSummaryDTO.SchedulerHealthDTO(
                name,
                schedulerStatus(lastRunAt, null, now),
                lastRunAt,
                null,
                freshness,
                null
        );
    }

    private DashboardSummaryDTO.SchedulerHealthDTO lockHealth(String name, ScheduledJobLock lock, Timestamp now) {
        if (lock == null) {
            return new DashboardSummaryDTO.SchedulerHealthDTO(name, "NEVER", null, null, null, null);
        }
        return new DashboardSummaryDTO.SchedulerHealthDTO(
                name,
                schedulerStatus(lock.getAcquiredAt(), lock.getLockedUntil(), now),
                lock.getAcquiredAt(),
                lock.getLockedUntil(),
                freshnessSeconds(lock.getAcquiredAt(), now),
                lock.getLockedBy()
        );
    }

    private String schedulerStatus(Timestamp lastRunAt, Timestamp lockedUntil, Timestamp now) {
        if (lockedUntil != null && lockedUntil.after(now)) return "RUNNING";
        if (lastRunAt == null) return "NEVER";
        long seconds = freshnessSeconds(lastRunAt, now);
        if (seconds <= 15 * 60) return "HEALTHY";
        if (seconds <= 2 * 60 * 60) return "LATE";
        return "STALE";
    }

    private Long freshnessSeconds(Timestamp timestamp, Timestamp now) {
        if (timestamp == null) return null;
        return Math.max(0, (now.getTime() - timestamp.getTime()) / 1000);
    }

    private List<DashboardSummaryDTO.RiskComplianceDTO> buildRiskCompliance(
            int totalVms,
            int driftedVms,
            int idleVms,
            DashboardSummaryDTO.CoverageDTO coverage,
            List<VmVolumeSnapshot> volumes) {
        List<DashboardSummaryDTO.RiskComplianceDTO> rows = new ArrayList<>();
        int unencryptedVolumes = (int) volumes.stream()
                .filter(volume -> Boolean.FALSE.equals(volume.getEncrypted()))
                .count();
        int unknownEncryptionVolumes = (int) volumes.stream()
                .filter(volume -> volume.getEncrypted() == null)
                .count();
        int missingMetricVms = totalVms - Math.round((coverage.metricsPercent() / 100.0f) * totalVms);
        int missingInventoryVms = totalVms - Math.round((coverage.inventoryPercent() / 100.0f) * totalVms);

        rows.add(new DashboardSummaryDTO.RiskComplianceDTO(
                unencryptedVolumes > 0 ? "danger" : "success",
                "EBS encryption",
                unencryptedVolumes > 0
                        ? unencryptedVolumes + " volume(s) are not encrypted."
                        : "No unencrypted EBS volumes detected.",
                unencryptedVolumes
        ));
        rows.add(new DashboardSummaryDTO.RiskComplianceDTO(
                driftedVms > 0 ? "danger" : "success",
                "State drift",
                driftedVms > 0
                        ? driftedVms + " VM(s) differ from expected state."
                        : "No state drift detected.",
                driftedVms
        ));
        rows.add(new DashboardSummaryDTO.RiskComplianceDTO(
                missingMetricVms > 0 ? "warning" : "success",
                "Metric coverage",
                coverage.metricsPercent() + "% of VMs have recent metric samples.",
                Math.max(0, missingMetricVms)
        ));
        rows.add(new DashboardSummaryDTO.RiskComplianceDTO(
                idleVms > 0 ? "warning" : "success",
                "Idle exposure",
                idleVms > 0
                        ? idleVms + " VM(s) may be waste candidates."
                        : "No idle VM exposure detected.",
                idleVms
        ));
        if (unknownEncryptionVolumes > 0 || missingInventoryVms > 0) {
            rows.add(new DashboardSummaryDTO.RiskComplianceDTO(
                    "info",
                    "Inventory gaps",
                    unknownEncryptionVolumes + " volume(s) have unknown encryption and " +
                            Math.max(0, missingInventoryVms) + " VM(s) are missing inventory.",
                    unknownEncryptionVolumes + Math.max(0, missingInventoryVms)
            ));
        }
        return rows;
    }

    private DashboardSummaryDTO.TopVmDTO toTopVm(Vm vm, VmIdleSummary idle, VmInventorySnapshot inventory) {
        Environment env = vm.getGroup().getEnvironment();
        return new DashboardSummaryDTO.TopVmDTO(
                vm.getVmId(),
                firstNonBlank(vm.getDisplayName(), vm.getName()),
                firstNonBlank(env.getDisplayName(), env.getName()),
                vm.getStatus() == null ? "UNKNOWN" : vm.getStatus().name(),
                idle == null ? null : idle.getLatestCpuUtilization(),
                idle == null ? null : idle.getIdleDurationMinutes(),
                inventory == null ? null : inventory.getTotalStorageGib()
        );
    }

    private List<DashboardSummaryDTO.RecommendationDTO> buildRecommendations(
            int totalVms,
            int driftedVms,
            int idleVms,
            DashboardSummaryDTO.CoverageDTO coverage,
            int pendingAccessRequests) {
        List<DashboardSummaryDTO.RecommendationDTO> items = new ArrayList<>();
        if (idleVms > 0) {
            items.add(new DashboardSummaryDTO.RecommendationDTO("warning", "Review idle VMs",
                    idleVms + " VM(s) show sustained low utilization.", idleVms));
        }
        if (driftedVms > 0) {
            items.add(new DashboardSummaryDTO.RecommendationDTO("danger", "Resolve state drift",
                    driftedVms + " VM(s) differ from their expected state.", driftedVms));
        }
        if (coverage.metricsPercent() < 90 && totalVms > 0) {
            items.add(new DashboardSummaryDTO.RecommendationDTO("info", "Improve metric coverage",
                    "CloudWatch samples exist for " + coverage.metricsPercent() + "% of VMs.", coverage.metricsPercent()));
        }
        if (pendingAccessRequests > 0) {
            items.add(new DashboardSummaryDTO.RecommendationDTO("primary", "Review access requests",
                    pendingAccessRequests + " request(s) are waiting for admin action.", pendingAccessRequests));
        }
        if (items.isEmpty()) {
            items.add(new DashboardSummaryDTO.RecommendationDTO("success", "Fleet looks healthy",
                    "No immediate dashboard checks require attention.", 0));
        }
        return items;
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }
}
