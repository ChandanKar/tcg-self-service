package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One "this month" for the fleet (U23-T01). Staging showed three month-to-date figures at the same
 * moment: Home $2,612, Cost management $2,404 and My account $2,467. Home's summary, Cost
 * management's month and My account's total now come from one definition: every environment's
 * snapshots from the 1st to yesterday (AWS billing where present, else the estimate) plus today's
 * live estimate.
 */
class FleetMonthToDateIntegrationTest extends SecuredWebTestBase {

    @Autowired private CostDailySnapshotRepository snapshots;
    @Autowired private ObjectMapper objectMapper;

    private void snapshot(Environment env, LocalDate day, String estimated, String actual) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(day));
        s.setEstimatedCost(new BigDecimal(estimated));
        s.setActualCost(actual == null ? null : new BigDecimal(actual));
        snapshots.saveAndFlush(s);
    }

    private JsonNode json(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void homeCostManagementAndMyAccountShowTheSameMonthToDate() throws Exception {
        LocalDate today = LocalDate.now();
        Environment running = newEnvironment("Fleet month");
        newVm(newGroup(running, "app"), "app-1", VmStatus.RUNNING);
        newVm(newGroup(running, "db"), "db-1", VmStatus.RUNNING);
        Environment retired = newEnvironment("Retired");
        BigDecimal expected = BigDecimal.ZERO;
        for (LocalDate day = today.withDayOfMonth(1); day.isBefore(today); day = day.plusDays(1)) {
            boolean billed = day.getDayOfMonth() == 1;
            snapshot(running, day, "10.00", billed ? "8.00" : null); // billing wins where present
            expected = expected.add(billed ? new BigDecimal("8.00") : new BigDecimal("10.00"));
        }
        // Last month never counts; a deactivated environment's days this month still do.
        snapshot(running, today.withDayOfMonth(1).minusDays(1), "99.00", null);
        if (today.getDayOfMonth() > 1) {
            snapshot(retired, today.minusDays(1), "5.00", null);
            expected = expected.add(new BigDecimal("5.00"));
        }
        retired.setIsActive(false);
        environmentRepository.saveAndFlush(retired);

        JsonNode month = json("/api/v1/cost-management/month");
        double home = json("/api/v1/dashboard/summary").path("summary").path("monthToDateCost").asDouble();
        double myAccount = json("/api/v1/users/me/cost").path("monthToDateTotal").asDouble();

        assertThat(month.path("completeDays").decimalValue()).isEqualByComparingTo(expected);
        double mtd = month.path("monthToDate").asDouble();
        assertThat(mtd).isGreaterThanOrEqualTo(expected.doubleValue());
        assertThat(mtd).isCloseTo(expected.doubleValue() + month.path("todaySoFar").asDouble(), within(0.001));
        // The live estimate moves by fractions of a cent between the requests.
        assertThat(home).isCloseTo(mtd, within(0.05));
        assertThat(myAccount).isCloseTo(mtd, within(0.05));
        assertThat(month.path("daysLeft").asInt()).isEqualTo(today.lengthOfMonth() - today.getDayOfMonth());
        if (today.getDayOfMonth() > 1) {
            // Forecast = month to date + the average of the last 7 complete days x the days left.
            assertThat(month.path("forecast").isNull()).isFalse();
            double average = month.path("dailyAverage").asDouble();
            assertThat(month.path("forecast").asDouble())
                    .isCloseTo(mtd + average * month.path("daysLeft").asInt(), within(0.01 * (1 + month.path("daysLeft").asInt())));
            // Day 1 is billed unless it is also the retired environment's (estimated) day.
            assertThat(month.path("billedDays").asInt()).isEqualTo(today.getDayOfMonth() > 2 ? 1 : 0);
        }
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * U24-T03: By team "this month" is the Month to date's definition. Staging's By team said
     * "CloudOps $2,641" (snapshots to yesterday) under a Month to date of $2,743; with one team owning
     * every environment the two are now the same figure.
     */
    @Test
    void byTeamThisMonthEqualsTheFleetMonthToDateForOneTeam() throws Exception {
        LocalDate today = LocalDate.now();
        Environment env = newEnvironment("Team month");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        for (LocalDate day = today.withDayOfMonth(1); day.isBefore(today); day = day.plusDays(1)) {
            snapshot(env, day, "10.00", day.getDayOfMonth() == 1 ? "8.00" : null);
        }
        snapshot(env, today.withDayOfMonth(1).minusDays(1), "99.00", null); // last month never counts
        jdbcTemplate.update("UPDATE environment SET team_id = NULL, metadata = ?", "{\"ownerTeam\":\"CloudOps\"}");

        JsonNode teams = json("/api/v1/cost-management/month-by-team");
        JsonNode month = json("/api/v1/cost-management/month");

        assertThat(teams.size()).isEqualTo(1);
        assertThat(teams.get(0).path("dimensionLabel").asText()).isEqualTo("CloudOps");
        // The live estimate moves by fractions of a cent between the requests.
        assertThat(teams.get(0).path("cost").asDouble()).isCloseTo(month.path("monthToDate").asDouble(), within(0.05));
        assertThat(teams.get(0).path("cost").asDouble()).isGreaterThanOrEqualTo(month.path("completeDays").asDouble());
    }
}
