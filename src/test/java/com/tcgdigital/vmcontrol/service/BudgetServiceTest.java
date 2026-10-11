package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Budget spend (actual preferred, team totals), the run-rate forecast and threshold rules (E20-T01). */
class BudgetServiceTest extends AbstractIntegrationTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Autowired private BudgetService budgets;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Budgeted");
    }

    private void day(Environment e, int dayOfMonth, String estimated, String actual) {
        jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost, actual_cost) "
                        + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), e.getEnvironmentId(),
                java.sql.Date.valueOf(SEPTEMBER.atDay(dayOfMonth)), new BigDecimal(estimated),
                actual == null ? null : new BigDecimal(actual));
    }

    private static Budget budget(BudgetScopeType type, String scopeId) {
        Budget b = new Budget();
        b.setBudgetId(UUID.randomUUID().toString());
        b.setScopeType(type);
        b.setScopeId(scopeId);
        b.setMonthlyAmount(new BigDecimal("1000.00"));
        return b;
    }

    @Test
    void spendPrefersActualsAndForecastsAtTheRecentRunRate() {
        for (int d = 1; d <= 10; d++) {
            day(env, d, "50.00", d <= 5 ? "40.00" : null);
        }
        day(env, 11, "999.00", null); // after "as of": not counted

        BudgetService.Spend spend = budgets.spend(budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId()),
                SEPTEMBER, SEPTEMBER.atDay(10));

        assertThat(spend.toDate()).isEqualByComparingTo("450.00");
        // The last 7 days (4..10): 40, 40, 50 x 5 = 330 / 7 = 47.1429 a day, for the 20 days left.
        assertThat(spend.forecast()).isEqualByComparingTo("1392.86");
        assertThat(spend.asOf()).isEqualTo(SEPTEMBER.atDay(10));
    }

    @Test
    void fortyADayForTenDaysForecastsTwelveHundred() {
        for (int d = 1; d <= 10; d++) {
            day(env, d, "40.00", null);
        }
        BudgetService.Spend spend = budgets.spend(budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId()),
                SEPTEMBER, SEPTEMBER.atDay(10));
        assertThat(spend.toDate()).isEqualByComparingTo("400.00");
        assertThat(spend.forecast()).isEqualByComparingTo("1200.00");
        assertThat(BudgetService.percent(spend.toDate(), new BigDecimal("1000"))).isEqualByComparingTo("40.0");
    }

    @Test
    void aTeamBudgetSumsItsActiveEnvironments() {
        Environment other = newEnvironment("Same team");
        Environment elsewhere = newEnvironment("Other team");
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id IN (?, ?)",
                "{\"ownerTeam\":\"Payments\"}", env.getEnvironmentId(), other.getEnvironmentId());
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id = ?",
                "{\"ownerTeam\":\"Search\"}", elsewhere.getEnvironmentId());
        day(env, 1, "10.00", null);
        day(other, 1, "20.00", "25.00");
        day(elsewhere, 1, "100.00", null);

        Budget team = budget(BudgetScopeType.TEAM, "Payments");
        assertThat(budgets.environmentIds(team)).containsExactlyInAnyOrder(env.getEnvironmentId(), other.getEnvironmentId());
        assertThat(budgets.spend(team, SEPTEMBER, SEPTEMBER.atDay(1)).toDate()).isEqualByComparingTo("35.00");
    }

    @Test
    void aBudgetWithNoDataHasNoForecast() {
        BudgetService.Spend spend = budgets.spend(budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId()),
                SEPTEMBER, SEPTEMBER.atDay(10));
        assertThat(spend.toDate()).isEqualByComparingTo("0");
        assertThat(spend.forecast()).isNull();
    }

    @Test
    void thresholdsAreOneToFourDifferentPercentagesAndSorted() {
        assertThat(BudgetService.validThresholds(null)).containsExactly(50, 75, 90, 100);
        assertThat(BudgetService.validThresholds(List.of(100, 80))).containsExactly(80, 100);
        for (List<Integer> bad : List.of(List.of(90, 50, 50), List.<Integer>of(), List.of(10, 20, 30, 40, 50),
                List.of(0), List.of(201))) {
            assertThatThrownBy(() -> BudgetService.validThresholds(bad))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Thresholds must be 1 to 4 different whole percentages between 1 and 200");
        }
    }

    @Test
    void forecastIsNullWithoutRecentDaysAndKeepsSpendOnTheLastDay() {
        day(env, 30, "10.00", null);
        BudgetService.Spend spend = budgets.spend(budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId()),
                SEPTEMBER, LocalDate.of(2026, 10, 3)); // a past month: as of its last day
        assertThat(spend.asOf()).isEqualTo(SEPTEMBER.atEndOfMonth());
        assertThat(spend.forecast()).isEqualByComparingTo("10.00");
    }
}
