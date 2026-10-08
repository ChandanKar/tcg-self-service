package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Idle evidence from metric samples (E16-T02): thresholds, network scaling by period, coverage,
 * stopped and EKS VMs, idle-since.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Mock private VmMetricSampleRepository samples;

    private IdleEvaluator evaluator;
    private IdleStopRule rule;
    private final List<VmMetricSample> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        evaluator = new IdleEvaluator(samples);
        rule = new IdleStopRule();
        rule.setIdleMinutes(60);
        rule.setCpuMaxPercent(new BigDecimal("5.00"));
        rule.setNetworkMbPerDay(new BigDecimal("5.00"));
        when(samples.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any()))
                .thenAnswer(inv -> stored.stream()
                        .filter(s -> ((List<?>) inv.getArgument(0)).contains(s.getVm().getVmId()))
                        .sorted(java.util.Comparator.comparing(VmMetricSample::getSampleTime))
                        .toList());
    }

    private static Vm vm(String id, VmStatus status) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setName(id);
        vm.setStatus(status);
        vm.setIsActive(true);
        vm.setProvider(CloudProvider.AWS);
        return vm;
    }

    /** One sample every {@code periodSeconds} from {@code minutesAgo} up to now. */
    private void samples(Vm vm, int minutesAgo, int periodSeconds, String cpu, long networkBytesPerSample) {
        for (Instant t = NOW.minus(Duration.ofMinutes(minutesAgo)); t.isBefore(NOW); t = t.plusSeconds(periodSeconds)) {
            sample(vm, t, periodSeconds, cpu, networkBytesPerSample);
        }
    }

    private void sample(Vm vm, Instant at, int periodSeconds, String cpu, long networkBytes) {
        VmMetricSample s = new VmMetricSample();
        s.setVm(vm);
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(periodSeconds);
        s.setCpuUtilization(new BigDecimal(cpu));
        s.setNetworkInBytes(networkBytes / 2);
        s.setNetworkOutBytes(networkBytes - networkBytes / 2);
        s.setDiskReadBytes(1_000L);
        s.setDiskWriteBytes(0L);
        stored.add(s);
    }

    @Test
    void twoQuietVmsForSeventyMinutesAreIdle() {
        Vm a = vm("a", VmStatus.RUNNING);
        Vm b = vm("b", VmStatus.RUNNING);
        samples(a, 70, 300, "1", 10_240);
        samples(b, 70, 300, "1", 10_240);

        EnvironmentIdleResult result = evaluator.evaluate(List.of(a, b), rule, NOW);

        assertThat(result.allIdle()).isTrue();
        assertThat(result.idleSince()).isEqualTo(NOW.minus(Duration.ofMinutes(70)));
        assertThat(result.vms()).allSatisfy(e -> {
            assertThat(e.idle()).isTrue();
            assertThat(e.coveragePercent()).isEqualTo(100);
            assertThat(e.reason()).isNull();
        });
        verify(samples, times(1)).findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any());
    }

    @Test
    void oneBusySampleTwentyMinutesAgoMakesItNotIdleAndNamesTheCpu() {
        Vm a = vm("a", VmStatus.RUNNING);
        samples(a, 70, 300, "1", 10_240);
        stored.removeIf(s -> s.getSampleTime().toInstant().equals(NOW.minus(Duration.ofMinutes(20))));
        sample(a, NOW.minus(Duration.ofMinutes(20)), 300, "12", 10_240);

        EnvironmentIdleResult result = evaluator.evaluate(List.of(a), rule, NOW);

        assertThat(result.allIdle()).isFalse();
        assertThat(result.vms().get(0).reason()).startsWith("CPU 12%");
        assertThat(result.vms().get(0).maxCpu()).isEqualByComparingTo("12");
    }

    @Test
    void halfAWindowOfSamplesIsInsufficientCoverage() {
        Vm a = vm("a", VmStatus.RUNNING);
        samples(a, 30, 300, "1", 10_240);

        EnvironmentIdleResult result = evaluator.evaluate(List.of(a), rule, NOW);

        assertThat(result.allIdle()).isFalse();
        assertThat(result.vms().get(0).reason()).startsWith("Insufficient metric coverage");
        assertThat(result.vms().get(0).coveragePercent()).isEqualTo(50);
    }

    @Test
    void theNetworkBudgetScalesWithTheSamplePeriod() {
        // 5 MB/day: about 18.2 KB per 300 s sample, about 3.6 KB per 60 s sample.
        assertThat(IdleEvaluator.networkBudget(rule, 300)).isEqualTo(18_204);
        assertThat(IdleEvaluator.networkBudget(rule, 60)).isEqualTo(3_641);
        Vm fiveMinute = vm("five", VmStatus.RUNNING);
        Vm oneMinute = vm("one", VmStatus.RUNNING);
        samples(fiveMinute, 60, 300, "1", 10_000); // under 18.2 KB
        samples(oneMinute, 60, 60, "1", 10_000);   // over 3.6 KB

        EnvironmentIdleResult result = evaluator.evaluate(List.of(fiveMinute, oneMinute), rule, NOW);

        assertThat(result.vms()).filteredOn(e -> e.vmId().equals("five")).singleElement()
                .satisfies(e -> assertThat(e.idle()).isTrue());
        assertThat(result.vms()).filteredOn(e -> e.vmId().equals("one")).singleElement()
                .satisfies(e -> assertThat(e.reason()).startsWith("Network"));
        assertThat(result.allIdle()).isFalse();
    }

    @Test
    void stoppedVmsAreIgnoredAndNothingRunningMeansNothingToDo() {
        Vm stopped = vm("s", VmStatus.STOPPED);

        EnvironmentIdleResult result = evaluator.evaluate(List.of(stopped), rule, NOW);

        assertThat(result.nothingRunning()).isTrue();
        assertThat(result.allIdle()).isFalse();
        verify(samples, never()).findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any());

        Vm running = vm("r", VmStatus.RUNNING);
        samples(running, 70, 300, "1", 100);
        assertThat(evaluator.evaluate(List.of(stopped, running), rule, NOW).allIdle()).isTrue();
    }

    @Test
    void eksNodeGroupsHaveNoMetricsAndBlockTheStop() {
        Vm eks = vm("cluster/workers", VmStatus.RUNNING);
        eks.setProvider(CloudProvider.AWS_EKS);
        Vm quiet = vm("q", VmStatus.RUNNING);
        samples(quiet, 70, 300, "1", 100);

        EnvironmentIdleResult result = evaluator.evaluate(List.of(eks, quiet), rule, NOW);

        assertThat(result.allIdle()).isFalse();
        assertThat(result.vms()).filteredOn(e -> e.vmId().equals("cluster/workers")).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("No metrics"));
    }

    @Test
    void idleSinceIsTheStartOfTheTrailingIdleRunAndTheLatestAcrossVms() {
        Vm a = vm("a", VmStatus.RUNNING);
        Vm b = vm("b", VmStatus.RUNNING);
        samples(a, 180, 300, "1", 100);                                      // idle for 3 h
        samples(b, 180, 300, "1", 100);
        stored.removeIf(s -> s.getVm() == b && s.getSampleTime().toInstant().equals(NOW.minus(Duration.ofMinutes(90))));
        sample(b, NOW.minus(Duration.ofMinutes(90)), 300, "40", 100);         // busy 90 min ago

        EnvironmentIdleResult result = evaluator.evaluate(List.of(a, b), rule, NOW);

        assertThat(result.allIdle()).isTrue();
        assertThat(result.vms()).filteredOn(e -> e.vmId().equals("a")).singleElement()
                .satisfies(e -> assertThat(e.idleSince()).isEqualTo(NOW.minus(Duration.ofMinutes(180))));
        assertThat(result.idleSince()).isEqualTo(NOW.minus(Duration.ofMinutes(85)));
    }
}
