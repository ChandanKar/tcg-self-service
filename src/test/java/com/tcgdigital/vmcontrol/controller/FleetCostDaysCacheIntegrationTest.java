package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentCostService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fleet's cost days are built once and shared for a few seconds (U26-T01). On staging an admin's
 * Home, Cost management and environment list asked for them about eight times at once (every
 * environment's snapshots and a live estimate of every VM each time) and the app was killed. With the
 * reuse on, Home, Cost management's month and By team, My account, the environment list and the
 * environment's own page show the same month to date to the cent; a snapshot or state change written
 * through the app is seen at once.
 */
class FleetCostDaysCacheIntegrationTest extends SecuredWebTestBase {

    @Autowired private EnvironmentCostService environmentCostService;
    @Autowired private CostDailySnapshotRepository snapshots;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reuseOn() {
        ReflectionTestUtils.invokeMethod(environmentCostService, "setFleetCacheSeconds", 300L);
    }

    @AfterEach
    void reuseOff() {
        ReflectionTestUtils.invokeMethod(environmentCostService, "setFleetCacheSeconds", 0L);
    }

    private void snapshot(Environment env, LocalDate day, String estimated) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(day));
        s.setEstimatedCost(new BigDecimal(estimated));
        snapshots.saveAndFlush(s);
    }

    /** A priced VM running since before today, so today's live estimate is more than zero. */
    private void pricedVm(Environment env, String name) {
        Vm vm = newVm(newGroup(env, name), name, VmStatus.RUNNING);
        jdbcTemplate.update("INSERT INTO vm_inventory_snapshot (inventory_id, vm_id, provider, provider_vm_id, instance_type, "
                + "last_refreshed_at) VALUES (?, ?, 'AWS', ?, 'm5.large', NOW())", UUID.randomUUID().toString(), vm.getVmId(),
                vm.getProviderVmId());
        jdbcTemplate.update("UPDATE vm SET created_at = ? WHERE vm_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(60))), vm.getVmId());
    }

    private JsonNode json(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void everyPageShowsTheSameMonthToDateToTheCent() throws Exception {
        LocalDate today = LocalDate.now();
        Environment a = newEnvironment("Cache A");
        Environment b = newEnvironment("Cache B");
        pricedVm(a, "a-app");
        pricedVm(b, "b-app");
        pricedVm(b, "b-db");
        for (LocalDate day = today.withDayOfMonth(1); day.isBefore(today); day = day.plusDays(1)) {
            snapshot(a, day, "10.00");
            snapshot(b, day, "4.25");
        }
        snapshot(a, today.withDayOfMonth(1).minusDays(1), "99.00"); // last month never counts

        JsonNode month = json("/api/v1/cost-management/month");
        BigDecimal mtd = month.path("monthToDate").decimalValue();
        assertThat(month.path("todaySoFar").decimalValue()).isPositive();

        BigDecimal teams = BigDecimal.ZERO;
        for (JsonNode team : json("/api/v1/cost-management/month-by-team")) {
            teams = teams.add(team.path("cost").decimalValue());
        }
        BigDecimal listed = BigDecimal.ZERO;
        for (JsonNode row : json("/api/v1/environments/page?page=0&size=100").path("content")) {
            listed = listed.add(row.path("monthToDateCost").decimalValue());
        }
        BigDecimal pages = json("/api/v1/environments/" + a.getEnvironmentId() + "/cost").path("monthToDateEstimated").decimalValue()
                .add(json("/api/v1/environments/" + b.getEnvironmentId() + "/cost").path("monthToDateEstimated").decimalValue());

        assertThat(json("/api/v1/dashboard/summary").path("summary").path("monthToDateCost").decimalValue()).isEqualByComparingTo(mtd);
        assertThat(json("/api/v1/users/me/cost").path("monthToDateTotal").decimalValue()).isEqualByComparingTo(mtd);
        assertThat(teams).isEqualByComparingTo(mtd);
        assertThat(listed).isEqualByComparingTo(mtd);
        assertThat(pages).isEqualByComparingTo(mtd);
    }

    @Test
    void aSnapshotWrittenByTheAppShowsAtOnceOneWrittenAroundItAfterARefresh() throws Exception {
        LocalDate today = LocalDate.now();
        Assumptions.assumeTrue(today.getDayOfMonth() > 2, "needs two complete days this month");
        Environment env = newEnvironment("Cache refresh");
        pricedVm(env, "app");
        BigDecimal before = myAccountRow();

        snapshot(env, today.minusDays(1), "7.00"); // written through the app: the shared days are dropped
        BigDecimal afterSave = myAccountRow();
        assertThat(afterSave.subtract(before).doubleValue()).isCloseTo(7.0, within(0.05));

        // A row written around the app is not seen while the shared days last...
        jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost) "
                + "VALUES (?, ?, ?, 5.00)", UUID.randomUUID().toString(), env.getEnvironmentId(), Date.valueOf(today.minusDays(2)));
        assertThat(myAccountRow()).isEqualByComparingTo(afterSave);

        // ...and is once they are built again.
        environmentCostService.refreshFleetCostDays();
        assertThat(myAccountRow().subtract(afterSave).doubleValue()).isCloseTo(5.0, within(0.05));
    }

    private BigDecimal myAccountRow() throws Exception {
        return json("/api/v1/users/me/cost").path("environments").get(0).path("monthToDateEstimated").decimalValue();
    }
}
