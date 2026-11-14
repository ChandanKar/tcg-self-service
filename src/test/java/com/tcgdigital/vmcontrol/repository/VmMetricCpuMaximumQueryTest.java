package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U26-T05, against MySQL (V104): the daily aggregate keeps the highest CPU Maximum next to the busiest
 * 5-minute average, and the rightsizing stats return the window's highest Maximum and how many days
 * with CPU data have none.
 */
class VmMetricCpuMaximumQueryTest extends AbstractIntegrationTest {

    @Autowired private VmMetricSampleRepository samples;
    @Autowired private VmMetricDailyRepository daily;

    private void sample(Vm vm, Instant at, String cpu, String cpuMaximum) {
        VmMetricSample s = new VmMetricSample();
        s.setMetricSampleId(UUID.randomUUID().toString());
        s.setVm(vm);
        s.setProvider(vm.getProvider());
        s.setProviderVmId(vm.getProviderVmId());
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(300);
        s.setCpuUtilization(new BigDecimal(cpu));
        s.setCpuMaximum(cpuMaximum == null ? null : new BigDecimal(cpuMaximum));
        samples.saveAndFlush(s);
    }

    private void day(Vm vm, LocalDate date, String maxCpu, String maxCpuMaximum) {
        VmMetricDaily d = new VmMetricDaily();
        d.setVm(vm);
        d.setBucketDate(Date.valueOf(date));
        d.setAvgCpuUtilization(maxCpu == null ? null : new BigDecimal("5"));
        d.setMaxCpuUtilization(maxCpu == null ? null : new BigDecimal(maxCpu));
        d.setMaxCpuMaximum(maxCpuMaximum == null ? null : new BigDecimal(maxCpuMaximum));
        d.setSampleCount(maxCpu == null ? 0 : 288);
        daily.saveAndFlush(d);
    }

    @Test
    void theDailyAggregateKeepsTheHighestMaximum() {
        Vm vm = newVm(newGroup(newEnvironment("Peak"), "app"), "a", VmStatus.RUNNING);
        Instant noon = Instant.parse("2026-11-02T12:00:00Z");
        sample(vm, noon, "26.390", "68.450");
        sample(vm, noon.plusSeconds(300), "20.000", "41.000");
        sample(vm, noon.plusSeconds(600), "25.000", null); // stored before the Maximum was kept

        VmMetricSampleRepository.DailyAggregate agg = samples.aggregateByVmForPeriod(
                Timestamp.from(noon.minusSeconds(3600)), Timestamp.from(noon.plusSeconds(3600))).get(0);

        assertThat(agg.getMaxCpu()).isEqualByComparingTo("26.39");
        assertThat(agg.getMaxCpuMaximum()).isEqualByComparingTo("68.45");
    }

    @Test
    void theStatsGiveTheHighestMaximumAndTheDaysWithoutOne() {
        VmGroup group = newGroup(newEnvironment("Stats"), "app");
        Vm mixed = newVm(group, "mixed", VmStatus.RUNNING);
        Vm old = newVm(group, "old", VmStatus.RUNNING);
        LocalDate today = LocalDate.now();
        day(mixed, today, "32.050", "68.450");
        day(mixed, today.minusDays(1), "30.000", "55.000");
        day(mixed, today.minusDays(2), "38.000", null); // before the Maximum was kept
        day(mixed, today.minusDays(3), null, null);     // stopped all day: not counted
        day(old, today, "20.000", null);

        List<VmMetricDailyRepository.UtilizationStats> stats =
                daily.findUtilizationStatsSince(List.of(mixed.getVmId(), old.getVmId()), Date.valueOf(today.minusDays(30)));

        VmMetricDailyRepository.UtilizationStats m = stats.stream().filter(s -> s.getVmId().equals(mixed.getVmId())).findFirst().orElseThrow();
        assertThat(m.getMaxCpu()).isEqualByComparingTo("38");
        assertThat(m.getMaxCpuMaximum()).isEqualByComparingTo("68.45");
        assertThat(m.getCpuDaysWithoutMaximum().longValue()).isEqualTo(1L);
        VmMetricDailyRepository.UtilizationStats o = stats.stream().filter(s -> s.getVmId().equals(old.getVmId())).findFirst().orElseThrow();
        assertThat(o.getMaxCpuMaximum()).isNull();
        assertThat(o.getCpuDaysWithoutMaximum().longValue()).isEqualTo(1L);
    }
}
