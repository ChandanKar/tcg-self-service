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

    public VmMetricsService(VmRepository vmRepository,
                            VmMetricSampleRepository sampleRepository,
                            VmIdleSummaryRepository idleSummaryRepository,
                            VmInventorySnapshotRepository inventoryRepository,
                            CloudMetricsProviderFactory providerFactory) {
        this.vmRepository = vmRepository;
        this.sampleRepository = sampleRepository;
        this.idleSummaryRepository = idleSummaryRepository;
        this.inventoryRepository = inventoryRepository;
        this.providerFactory = providerFactory;
    }

    @Transactional
    public int syncRunningVmMetrics() {
        List<Vm> runningVms = vmRepository.findByStatus(VmStatus.RUNNING);
        Map<String, List<Vm>> groups = runningVms.stream()
                .filter(vm -> vm.getProviderVmId() != null && !vm.getProviderVmId().isBlank())
                .collect(Collectors.groupingBy(vm -> vm.getProvider().name() + ":" + vm.getRegion()));

        Instant end = Instant.now();
        Instant start = end.minusSeconds((long) periodSeconds * 3);
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
            Map<String, CloudMetricsProviderService.VmMetricData> metrics =
                    service.fetchLatestMetrics(ids, region, start, end, periodSeconds);
            for (Vm vm : entry.getValue()) {
                CloudMetricsProviderService.VmMetricData data = metrics.get(vm.getProviderVmId());
                if (data == null || data.getSampleTime() == null) continue;
                if (saveMetricSample(vm, data)) saved++;
                updateIdleSummary(vm);
            }
        }
        log.info("VM metrics sync completed: {} sample(s) saved", saved);
        return saved;
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
        return VmMetricsDTO.from(vmId, window, requestedPeriodSeconds, idleSummary, samples);
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
