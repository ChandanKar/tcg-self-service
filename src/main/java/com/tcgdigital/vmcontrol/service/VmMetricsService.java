package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.VmMetricsDTO;
import com.tcgdigital.vmcontrol.dto.VmUtilizationSummaryDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class VmMetricsService {

    private static final Logger log = LoggerFactory.getLogger(VmMetricsService.class);

    private final VmRepository vmRepository;
    private final VmMetricSampleRepository sampleRepository;
    private final VmIdleSummaryRepository idleSummaryRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final CloudMetricsProviderFactory providerFactory;

    @Value("${vm.metrics.period-seconds:300}")
    private int periodSeconds;

    @Value("${vm.metrics.idle.cpu-threshold-percent:5}")
    private BigDecimal idleCpuThresholdPercent;

    @Value("${vm.metrics.idle.network-threshold-bytes-per-period:1048576}")
    private long idleNetworkThresholdBytes;

    @Value("${vm.metrics.idle.disk-threshold-bytes-per-period:1048576}")
    private long idleDiskThresholdBytes;

    @Value("${vm.metrics.idle.minimum-duration-minutes:30}")
    private int idleMinimumDurationMinutes;

    /** How far back a sync fills gaps since each VM's latest stored sample (E12-T03). */
    @Value("${vm.metrics.backfill-max-hours:3}")
    private int backfillMaxHours = 3;

    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public VmMetricsService(VmRepository vmRepository,
                            VmMetricSampleRepository sampleRepository,
                            VmIdleSummaryRepository idleSummaryRepository,
                            VmInventorySnapshotRepository inventoryRepository,
                            CloudMetricsProviderFactory providerFactory,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.vmRepository = vmRepository;
        this.sampleRepository = sampleRepository;
        this.idleSummaryRepository = idleSummaryRepository;
        this.inventoryRepository = inventoryRepository;
        this.providerFactory = providerFactory;
    }

    /**
     * Stores every CloudWatch datapoint since each group's oldest latest-stored sample (at most
     * vm.metrics.backfill-max-hours back), so a missed run's gap is filled (E12-T03). Not
     * transactional: the CloudWatch calls run outside any transaction and each sample is saved in
     * its own short one.
     *
     * @return how many samples were saved
     */
    public int syncRunningVmMetrics() {
        List<Vm> runningVms = vmRepository.findByStatus(VmStatus.RUNNING);
        Map<String, List<Vm>> groups = runningVms.stream()
                .filter(vm -> vm.getProviderVmId() != null && !vm.getProviderVmId().isBlank())
                .collect(Collectors.groupingBy(vm -> vm.getProvider().name() + ":" + vm.getRegion()));

        Instant end = Instant.now();
        int saved = 0;

        for (Map.Entry<String, List<Vm>> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            CloudProvider provider = CloudProvider.valueOf(parts[0]);
            String region = parts.length > 1 ? parts[1] : null;
            CloudMetricsProviderService service = providerFactory.getService(provider).orElse(null);
            if (service == null || !service.isAvailable()) {
                log.debug("Metrics provider {} is unavailable; skipping {} VM(s)", provider, entry.getValue().size());
                continue;
            }

            List<String> ids = entry.getValue().stream().map(Vm::getProviderVmId).toList();
            Instant start = windowStart(entry.getValue(), end);
            Map<String, List<CloudMetricsProviderService.VmMetricData>> series =
                    service.fetchMetricSeries(ids, region, start, end, periodSeconds);
            for (Vm vm : entry.getValue()) {
                List<CloudMetricsProviderService.VmMetricData> points = series.getOrDefault(vm.getProviderVmId(), List.of());
                if (points.isEmpty()) continue;
                for (CloudMetricsProviderService.VmMetricData data : points) {
                    if (data.getSampleTime() == null) continue;
                    Boolean stored = transactions.execute(status -> saveMetricSample(vm, data));
                    if (Boolean.TRUE.equals(stored)) saved++;
                }
                transactions.executeWithoutResult(status -> updateIdleSummary(vm));
            }
        }
        log.info("VM metrics sync completed: {} sample(s) saved", saved);
        return saved;
    }

    /**
     * From the oldest of the group's latest stored samples, but never more than
     * backfill-max-hours back and never less than three periods (E12-T03).
     */
    private Instant windowStart(List<Vm> vms, Instant end) {
        Instant cap = end.minus(Duration.ofHours(Math.max(1, backfillMaxHours)));
        Instant recent = end.minusSeconds((long) periodSeconds * 3);
        Map<String, Timestamp> latestByVm = new java.util.HashMap<>();
        for (VmMetricSample s : sampleRepository.findLatestByVmIds(vms.stream().map(Vm::getVmId).toList())) {
            latestByVm.merge(s.getVm().getVmId(), s.getSampleTime(), (a, b) -> a.after(b) ? a : b);
        }
        Instant start = recent;
        for (Vm vm : vms) {
            Timestamp latest = latestByVm.get(vm.getVmId());
            Instant from = latest == null ? cap : latest.toInstant().plusSeconds(1);
            if (from.isBefore(start)) {
                start = from;
            }
        }
        return start.isBefore(cap) ? cap : start;
    }

    @Transactional(readOnly = true)
    public VmMetricsDTO getMetrics(String environmentId, String vmId, String window, int requestedPeriodSeconds) {
        validateVmInEnvironment(environmentId, vmId);
        Instant end = Instant.now();
        Instant start = end.minus(parseWindow(window));
        List<VmMetricSample> samples = sampleRepository
                .findByVmVmIdAndSampleTimeBetweenOrderBySampleTimeAsc(
                        vmId, Timestamp.from(start), Timestamp.from(end));
        VmIdleSummary idleSummary = idleSummaryRepository.findByVmVmId(vmId).orElse(null);
        if (!ALLOWED_PERIODS.contains(requestedPeriodSeconds)) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException(
                    "period must be one of " + ALLOWED_PERIODS + " seconds");
        }
        int storedPeriod = samples.isEmpty() || samples.get(0).getPeriodSeconds() == null
                ? periodSeconds : samples.get(0).getPeriodSeconds();
        if (requestedPeriodSeconds <= storedPeriod) {
            return VmMetricsDTO.from(vmId, window, storedPeriod, idleSummary, samples);
        }
        return VmMetricsDTO.fromSeries(vmId, window, requestedPeriodSeconds, idleSummary,
                bucket(samples, requestedPeriodSeconds));
    }

    /** Periods the metrics endpoint accepts (E12-T04). */
    static final java.util.Set<Integer> ALLOWED_PERIODS = new java.util.TreeSet<>(java.util.List.of(300, 900, 3600));

    /**
     * One point per {@code period}: CPU and memory averaged over the bucket's samples, byte
     * counters summed, stamped with the bucket start (E12-T04).
     */
    static List<VmMetricsDTO.SampleDTO> bucket(List<VmMetricSample> samples, int period) {
        Map<Long, List<VmMetricSample>> byBucket = new java.util.TreeMap<>();
        for (VmMetricSample s : samples) {
            long start = Math.floorDiv(s.getSampleTime().toInstant().getEpochSecond(), period) * period;
            byBucket.computeIfAbsent(start, k -> new java.util.ArrayList<>()).add(s);
        }
        List<VmMetricsDTO.SampleDTO> points = new java.util.ArrayList<>();
        byBucket.forEach((start, group) -> {
            VmMetricsDTO.SampleDTO p = new VmMetricsDTO.SampleDTO();
            p.setSampleTime(Timestamp.from(Instant.ofEpochSecond(start)));
            p.setCpuUtilization(average(group, VmMetricSample::getCpuUtilization));
            p.setMemoryUtilization(average(group, VmMetricSample::getMemoryUtilization));
            p.setNetworkInBytes(sum(group, VmMetricSample::getNetworkInBytes));
            p.setNetworkOutBytes(sum(group, VmMetricSample::getNetworkOutBytes));
            p.setDiskReadBytes(sum(group, VmMetricSample::getDiskReadBytes));
            p.setDiskWriteBytes(sum(group, VmMetricSample::getDiskWriteBytes));
            points.add(p);
        });
        return points;
    }

    private static BigDecimal average(List<VmMetricSample> group, java.util.function.Function<VmMetricSample, BigDecimal> value) {
        List<BigDecimal> values = group.stream().map(value).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 3, java.math.RoundingMode.HALF_UP);
    }

    private static Long sum(List<VmMetricSample> group, java.util.function.Function<VmMetricSample, Long> value) {
        List<Long> values = group.stream().map(value).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
    }

    @Transactional(readOnly = true)
    public VmUtilizationSummaryDTO getUtilizationSummary(String environmentId, String vmId) {
        validateVmInEnvironment(environmentId, vmId);
        return VmUtilizationSummaryDTO.from(
                vmId,
                inventoryRepository.findByVmVmId(vmId).orElse(null),
                idleSummaryRepository.findByVmVmId(vmId).orElse(null));
    }

    private boolean saveMetricSample(Vm vm, CloudMetricsProviderService.VmMetricData data) {
        Timestamp sampleTime = data.getSampleTime();
        int samplePeriod = data.getPeriodSeconds() != null ? data.getPeriodSeconds() : periodSeconds;
        if (sampleRepository.existsByVmVmIdAndSampleTimeAndPeriodSeconds(vm.getVmId(), sampleTime, samplePeriod)) {
            return false;
        }

        VmMetricSample sample = new VmMetricSample();
        sample.setVm(vm);
        sample.setProvider(vm.getProvider());
        sample.setProviderVmId(vm.getProviderVmId());
        sample.setSampleTime(sampleTime);
        sample.setPeriodSeconds(samplePeriod);
        sample.setCpuUtilization(data.getCpuUtilization());
        sample.setMemoryUtilization(data.getMemoryUtilization());
        sample.setNetworkInBytes(data.getNetworkInBytes());
        sample.setNetworkOutBytes(data.getNetworkOutBytes());
        sample.setDiskReadBytes(data.getDiskReadBytes());
        sample.setDiskWriteBytes(data.getDiskWriteBytes());
        sample.setStatusAtSample(vm.getStatus() != null ? vm.getStatus().name() : null);

        try {
            sampleRepository.save(sample);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    private void updateIdleSummary(Vm vm) {
        int sampleCount = Math.max(1, (idleMinimumDurationMinutes * 60 + periodSeconds - 1) / periodSeconds);
        List<VmMetricSample> recent = sampleRepository.findByVmVmIdOrderBySampleTimeDesc(
                vm.getVmId(), org.springframework.data.domain.PageRequest.of(0, sampleCount));
        if (recent.isEmpty()) return;

        VmMetricSample latest = recent.stream()
                .max(Comparator.comparing(VmMetricSample::getSampleTime))
                .orElse(recent.get(0));

        boolean enoughSamples = recent.size() >= sampleCount;
        boolean allIdle = enoughSamples && recent.stream().allMatch(this::isSampleIdle);
        VmIdleSummary summary = idleSummaryRepository.findByVmVmId(vm.getVmId())
                .orElseGet(VmIdleSummary::new);
        summary.setVm(vm);
        summary.setLatestCpuUtilization(latest.getCpuUtilization());
        summary.setLatestNetworkInBytes(latest.getNetworkInBytes());
        summary.setLatestNetworkOutBytes(latest.getNetworkOutBytes());
        summary.setLatestDiskReadBytes(latest.getDiskReadBytes());
        summary.setLatestDiskWriteBytes(latest.getDiskWriteBytes());
        summary.setLatestSampleTime(latest.getSampleTime());
        summary.setIdle(allIdle);
        if (allIdle) {
            Timestamp idleSince = recent.stream()
                    .min(Comparator.comparing(VmMetricSample::getSampleTime))
                    .map(VmMetricSample::getSampleTime)
                    .orElse(latest.getSampleTime());
            summary.setIdleSince(idleSince);
            long minutes = Duration.between(idleSince.toInstant(), latest.getSampleTime().toInstant()).toMinutes();
            summary.setIdleDurationMinutes((int) Math.max(minutes, idleMinimumDurationMinutes));
            summary.setReason("Below configured CPU, network, and disk IO thresholds");
        } else {
            summary.setIdleSince(null);
            summary.setIdleDurationMinutes(0);
            summary.setReason(enoughSamples ? "Recent activity is above idle thresholds" : "Insufficient metric samples");
        }
        idleSummaryRepository.save(summary);
    }

    private boolean isSampleIdle(VmMetricSample sample) {
        BigDecimal cpu = sample.getCpuUtilization();
        long network = safe(sample.getNetworkInBytes()) + safe(sample.getNetworkOutBytes());
        long disk = safe(sample.getDiskReadBytes()) + safe(sample.getDiskWriteBytes());
        return cpu != null
                && cpu.compareTo(idleCpuThresholdPercent) < 0
                && network < idleNetworkThresholdBytes
                && disk < idleDiskThresholdBytes;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private Duration parseWindow(String window) {
        return switch ((window == null ? "1h" : window).toLowerCase()) {
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            default -> Duration.ofHours(1);
        };
    }

    private Vm validateVmInEnvironment(String environmentId, String vmId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("Vm", vmId));
        String actualEnvironmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        if (!actualEnvironmentId.equals(environmentId)) {
            throw new ResourceNotFoundException("Vm not found in environment: " + vmId);
        }
        return vm;
    }
}
