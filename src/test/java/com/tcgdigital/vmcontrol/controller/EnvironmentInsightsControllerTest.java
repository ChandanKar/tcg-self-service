package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U08-T08: environment insights for 24 h or 7 days, with averages over the window, and the weekend
 * idle query on MySQL (DAYOFWEEK).
 */
@AutoConfigureMockMvc
class EnvironmentInsightsControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VmMetricSampleRepository sampleRepository;

    private void sample(Vm vm, Instant at, String cpu) {
        VmMetricSample s = new VmMetricSample();
        s.setVm(vm);
        s.setProvider(CloudProvider.AWS);
        s.setProviderVmId(vm.getProviderVmId());
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(300);
        s.setCpuUtilization(new BigDecimal(cpu));
        sampleRepository.saveAndFlush(s);
    }

    @Test
    void sevenDaysAveragesOverTheWeekAndOtherWindowsAreRefused() throws Exception {
        Environment env = newEnvironment("Insights window");
        Vm vm = newVm(newGroup(env, "app"), "pay-api", VmStatus.RUNNING);
        Instant now = Instant.now();
        sample(vm, now.minusSeconds(3 * 86400), "90");   // in the 7 days, not in the last 24 h
        sample(vm, now.minusSeconds(600), "10");

        String url = "/api/v1/environments/" + env.getEnvironmentId() + "/insights";
        mockMvc.perform(get(url + "?window=7d").header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("7d"))
                .andExpect(jsonPath("$.busiestVms[0].avgCpuWindow").value(50.0))
                .andExpect(jsonPath("$.actions").isArray());
        mockMvc.perform(get(url).header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("24h"))
                .andExpect(jsonPath("$.busiestVms[*].avgCpuWindow", contains(10.0)));
        mockMvc.perform(get(url + "?window=30d").header("X-User-Id", "admin-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weekendIdleTimeCountsOnlySaturdaysAndSundaysUnderTheThreshold() {
        Environment env = newEnvironment("Insights weekend");
        Vm vm = newVm(newGroup(env, "app"), "batch", VmStatus.RUNNING);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate saturday = today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));
        LocalDate wednesday = today.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
        sample(vm, saturday.atTime(12, 0).toInstant(ZoneOffset.UTC), "2");
        sample(vm, saturday.atTime(13, 0).toInstant(ZoneOffset.UTC), "40");     // busy: not idle
        sample(vm, wednesday.atTime(12, 0).toInstant(ZoneOffset.UTC), "1");     // a weekday

        List<VmMetricSampleRepository.VmIdleSeconds> rows = sampleRepository.weekendIdleSecondsByVmIds(
                List.of(vm.getVmId()), Timestamp.from(Instant.now().minusSeconds(8 * 86400)), BigDecimal.valueOf(5));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVmId()).isEqualTo(vm.getVmId());
        assertThat(rows.get(0).getSeconds().longValue()).isEqualTo(300L);
    }
}
