package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides from vm_metric_sample whether a rule's running VMs are idle (E16-T02, G5), using the
 * AWS Compute Optimizer idle definition: CPU below the threshold and network below a per-day
 * budget. Disk IO is recorded but not required. Read-only.
 *
 * <p>VmIdleSummary is not used: it is recomputed over a fixed 30-minute window, so its idle
 * duration never grows past that.
 */
@Component
public class IdleEvaluator {

    /** Samples must cover at least this share of the window. */
    static final int MIN_COVERAGE_PERCENT = 80;
    private static final long BYTES_PER_MB = 1_048_576L;
    private static final Duration MIN_LOOKBACK = Duration.ofHours(24);

    private final VmMetricSampleRepository sampleRepository;

    public IdleEvaluator(VmMetricSampleRepository sampleRepository) {
        this.sampleRepository = sampleRepository;
    }

    /**
     * @param vmsInScope the rule's VMs (any status); only active RUNNING ones are evaluated
     */
    public EnvironmentIdleResult evaluate(List<Vm> vmsInScope, IdleStopRule rule, Instant now) {
        List<Vm> running = vmsInScope.stream()
                .filter(vm -> Boolean.TRUE.equals(vm.getIsActive()) && vm.getStatus() == VmStatus.RUNNING)
                .toList();
        if (running.isEmpty()) {
            return new EnvironmentIdleResult(false, null, List.of());
        }

        Duration window = Duration.ofMinutes(rule.getIdleMinutes());
        Duration lookback = window.compareTo(MIN_LOOKBACK) > 0 ? window : MIN_LOOKBACK;
        Map<String, List<VmMetricSample>> samplesByVm = new HashMap<>();
        // One query for every VM over the lookback; the window is a suffix of it.
        sampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(
                        running.stream().map(Vm::getVmId).toList(), Timestamp.from(now.minus(lookback)), Timestamp.from(now))
                .forEach(s -> samplesByVm.computeIfAbsent(s.getVm().getVmId(), k -> new ArrayList<>()).add(s));

        List<VmIdleEvidence> evidence = new ArrayList<>();
        for (Vm vm : running) {
            evidence.add(evaluateVm(vm, samplesByVm.getOrDefault(vm.getVmId(), List.of()), rule, now, window));
        }
        boolean allIdle = evidence.stream().allMatch(VmIdleEvidence::idle);
        Instant idleSince = allIdle
                ? evidence.stream().map(VmIdleEvidence::idleSince).max(Comparator.naturalOrder()).orElse(null)
                : null;
        return new EnvironmentIdleResult(allIdle, idleSince, evidence);
    }

    private VmIdleEvidence evaluateVm(Vm vm, List<VmMetricSample> lookbackSamples, IdleStopRule rule,
                                      Instant now, Duration window) {
        String name = vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName();
        if (vm.getProvider() == CloudProvider.AWS_EKS) {
            // Node groups have no per-instance metrics (G15): never treated as idle.
            return new VmIdleEvidence(vm.getVmId(), name, false, null, null, 0, 0, 0, 0, "No metrics");
        }
        Instant windowStart = now.minus(window);
        List<VmMetricSample> inWindow = lookbackSamples.stream()
                .filter(s -> !s.getSampleTime().toInstant().isBefore(windowStart))
                .toList();

        BigDecimal maxCpu = null;
        long network = 0;
        long disk = 0;
        long coveredSeconds = 0;
        String reason = null;
        for (VmMetricSample s : inWindow) {
            if (s.getCpuUtilization() != null && (maxCpu == null || s.getCpuUtilization().compareTo(maxCpu) > 0)) {
                maxCpu = s.getCpuUtilization();
            }
            network += networkBytes(s);
            disk += nz(s.getDiskReadBytes()) + nz(s.getDiskWriteBytes());
            coveredSeconds += period(s);
            if (reason == null) {
                reason = notIdleReason(s, rule);
            }
        }
        int coverage = (int) Math.min(100, coveredSeconds * 100 / Math.max(1, window.getSeconds()));
        if (inWindow.isEmpty()) {
            reason = "No metrics";
        } else if (reason == null && coverage < MIN_COVERAGE_PERCENT) {
            reason = "Insufficient metric coverage (" + coverage + "% of the window)";
        }
        Instant idleSince = reason == null ? trailingIdleStart(lookbackSamples, rule) : null;
        return new VmIdleEvidence(vm.getVmId(), name, reason == null, idleSince, maxCpu, network, disk,
                inWindow.size(), coverage, reason);
    }

    /** Null when the sample is idle; otherwise what made it busy. */
    private static String notIdleReason(VmMetricSample s, IdleStopRule rule) {
        if (s.getCpuUtilization() == null) {
            return "No CPU data at " + s.getSampleTime().toInstant();
        }
        if (s.getCpuUtilization().compareTo(rule.getCpuMaxPercent()) >= 0) {
            return "CPU " + s.getCpuUtilization().stripTrailingZeros().toPlainString() + "% at "
                    + s.getSampleTime().toInstant() + " (threshold " + rule.getCpuMaxPercent().stripTrailingZeros().toPlainString() + "%)";
        }
        long budget = networkBudget(rule, period(s));
        if (networkBytes(s) >= budget) {
            return "Network " + networkBytes(s) + " bytes in " + period(s) + "s at " + s.getSampleTime().toInstant()
                    + " (threshold " + budget + ")";
        }
        return null;
    }

    /**
     * Start of the longest trailing run of idle samples: walk back from the newest sample while
     * samples are idle and contiguous (a gap of more than two periods ends the run).
     */
    private static Instant trailingIdleStart(List<VmMetricSample> samples, IdleStopRule rule) {
        Instant start = null;
        Instant previous = null;
        for (int i = samples.size() - 1; i >= 0; i--) {
            VmMetricSample s = samples.get(i);
            Instant at = s.getSampleTime().toInstant();
            if (notIdleReason(s, rule) != null) {
                break;
            }
            if (previous != null && Duration.between(at, previous).getSeconds() > 2L * period(s)) {
                break;
            }
            start = at;
            previous = at;
        }
        return start;
    }

    /** The per-day network budget scaled to one sample's period. */
    static long networkBudget(IdleStopRule rule, long periodSeconds) {
        return rule.getNetworkMbPerDay().multiply(BigDecimal.valueOf(BYTES_PER_MB))
                .multiply(BigDecimal.valueOf(periodSeconds))
                .divide(BigDecimal.valueOf(86_400), 0, java.math.RoundingMode.HALF_UP)
                .longValue();
    }

    private static long networkBytes(VmMetricSample s) {
        return nz(s.getNetworkInBytes()) + nz(s.getNetworkOutBytes());
    }

    private static long period(VmMetricSample s) {
        return s.getPeriodSeconds() != null && s.getPeriodSeconds() > 0 ? s.getPeriodSeconds() : 300;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
