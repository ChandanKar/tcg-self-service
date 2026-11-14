package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Home's fleet utilization and its trend (U26-T01) are summed per sample time in the database instead
 * of loading every sample (180 VMs x 288 samples a day cost about 130 MB of heap per call on a
 * staging-sized fleet). The figures are those of every sample: averages over the non-null values,
 * byte totals, the latest sample time and seven buckets between the first and the last sample.
 */
class DashboardUtilizationIntegrationTest extends SecuredWebTestBase {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void sample(Vm vm, Instant at, String cpu, String memory, Long networkIn) {
        jdbcTemplate.update("INSERT INTO vm_metric_sample (metric_sample_id, vm_id, provider, provider_vm_id, sample_time, "
                        + "period_seconds, cpu_utilization, memory_utilization, network_in_bytes) VALUES (?, ?, 'AWS', ?, ?, 300, ?, ?, ?)",
                UUID.randomUUID().toString(), vm.getVmId(), vm.getProviderVmId(), Timestamp.from(at),
                cpu == null ? null : new java.math.BigDecimal(cpu), memory == null ? null : new java.math.BigDecimal(memory), networkIn);
    }

    @Test
    void utilizationAndTrendCountEverySample() throws Exception {
        Environment env = newEnvironment("Utilization");
        Vm a = newVm(newGroup(env, "app"), "a", VmStatus.RUNNING);
        Vm b = newVm(newGroup(env, "db"), "b", VmStatus.RUNNING);
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(70, ChronoUnit.MINUTES);
        Instant t1 = t0.plus(30, ChronoUnit.MINUTES);  // bucket 3 of 7 (30 of 60 minutes)
        Instant t2 = t0.plus(60, ChronoUnit.MINUTES);  // the last bucket
        sample(a, t0, "10.00", "50.00", 100L);
        sample(b, t0, "20.00", null, 200L);
        sample(a, t1, "30.00", null, 300L);
        sample(b, t1, null, null, null);
        sample(a, t2, "5.55", "40.00", 1L);
        sample(b, t2, "4.45", "60.00", 2L);
        sample(a, t0.minus(25, ChronoUnit.HOURS), "99.00", "99.00", 9999L); // older than 24 hours: not counted

        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode utilization = body.path("utilization");
        assertThat(utilization.path("avgCpuUtilization").decimalValue()).isEqualByComparingTo("14.0"); // 70 / 5 values
        assertThat(utilization.path("totalNetworkInBytes").asLong()).isEqualTo(603L);
        assertThat(utilization.path("totalNetworkOutBytes").asLong()).isZero();
        assertThat(utilization.path("latestMetricSampleTime").isNull()).isFalse();
        JsonNode latest = utilization.path("latestMetricSampleTime");
        assertThat(latest.isNumber() ? Instant.ofEpochMilli(latest.asLong()) : java.time.OffsetDateTime.parse(latest.asText()).toInstant())
                .isEqualTo(t2);

        JsonNode trend = body.path("utilizationTrend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(0).path("cpuUtilization").decimalValue()).isEqualByComparingTo("15.0");
        assertThat(trend.get(0).path("memoryUtilization").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(trend.get(0).path("networkInBytes").asLong()).isEqualTo(300L);
        assertThat(trend.get(3).path("cpuUtilization").decimalValue()).isEqualByComparingTo("30.0");
        assertThat(trend.get(3).path("memoryUtilization").isNull()).isTrue();
        assertThat(trend.get(3).path("networkInBytes").asLong()).isEqualTo(300L);
        assertThat(trend.get(6).path("cpuUtilization").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(trend.get(6).path("memoryUtilization").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(trend.get(6).path("networkInBytes").asLong()).isEqualTo(3L);
        for (int empty : new int[] {1, 2, 4, 5}) {
            assertThat(trend.get(empty).path("cpuUtilization").isNull()).isTrue();
            assertThat(trend.get(empty).path("networkInBytes").asLong()).isZero();
        }
    }
}
