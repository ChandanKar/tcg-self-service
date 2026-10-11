package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetAlert;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.BudgetAlertRepository;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget alerts (E20-T02): each threshold fires once a month, the forecast alert fires when the
 * run rate overshoots, a re-run sends nothing new, a new month fires again, and a team's owners
 * hear each alert once.
 */
class BudgetEvaluationServiceTest extends AbstractIntegrationTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Autowired private BudgetEvaluationService evaluation;
    @Autowired private BudgetRepository budgets;
    @Autowired private BudgetAlertRepository alerts;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private User owner;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Alerted");
        owner = newUser("budget-owner@example.com", false, false);
        grant(owner, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);
    }

    private void spend(Environment e, YearMonth month, int days, String perDay) {
        for (int d = 1; d <= days; d++) {
            jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost) "
                    + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), e.getEnvironmentId(),
                    java.sql.Date.valueOf(month.atDay(d)), new BigDecimal(perDay));
        }
    }

    private Budget budget(BudgetScopeType type, String scopeId, boolean forecastAlert) {
        Budget b = new Budget();
        b.setBudgetId(UUID.randomUUID().toString());
        b.setScopeType(type);
        b.setScopeId(scopeId);
        b.setMonthlyAmount(new BigDecimal("1000.00"));
        b.setForecastAlert(forecastAlert);
        b.setCreatedByUserId(owner.getUserId());
        return budgets.saveAndFlush(b);
    }

    private void alreadyFired(Budget b, YearMonth month, int threshold) {
        BudgetAlert a = new BudgetAlert();
        a.setAlertId(UUID.randomUUID().toString());
        a.setBudget(b);
        a.setBudgetMonth(month.toString());
        a.setThreshold(threshold);
        a.setKind(BudgetAlert.Kind.ACTUAL);
        alerts.saveAndFlush(a);
    }

    private List<String> titlesFor(User user, String type) {
        return jdbcTemplate.queryForList("SELECT title FROM notification WHERE user_id = ? AND type = ? ORDER BY created_at",
                String.class, user.getUserId(), type);
    }

    @Test
    void reachingSeventyFivePercentAlertsTheOwnersOnceAndNotNinety() {
        Budget b = budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId(), false);
        alreadyFired(b, SEPTEMBER, 50);
        spend(env, SEPTEMBER, 19, "40.00"); // $760

        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(19))).isEqualTo(1);

        assertThat(titlesFor(owner, "BUDGET_THRESHOLD")).containsExactly("Budget 75% used: " + env.getDisplayName());
        assertThat(jdbcTemplate.queryForObject("SELECT message FROM notification WHERE user_id = ? AND type = 'BUDGET_THRESHOLD'",
                String.class, owner.getUserId())).isEqualTo(env.getDisplayName() + " has spent $760.00 of its $1,000.00 budget for September 2026.");
        // Admins hear it too.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification n JOIN app_user u ON u.user_id = n.user_id "
                + "WHERE u.admin = TRUE AND n.type = 'BUDGET_THRESHOLD'", Integer.class)).isPositive();

        // The same day again: nothing new.
        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(19))).isZero();
        assertThat(titlesFor(owner, "BUDGET_THRESHOLD")).hasSize(1);
    }

    @Test
    void crossingSeveralThresholdsAtOnceFiresEachOnce() {
        Budget b = budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId(), false);
        spend(env, SEPTEMBER, 10, "95.00"); // $950: 50, 75 and 90

        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(10))).isEqualTo(3);
        assertThat(alerts.findByBudgetBudgetIdAndBudgetMonthOrderByFiredAtAsc(b.getBudgetId(), "2026-09"))
                .extracting(BudgetAlert::getThreshold).containsExactlyInAnyOrder(50, 75, 90);
    }

    @Test
    void aForecastOverrunAlertsOnceAndANewMonthStartsAfresh() {
        Budget b = budget(BudgetScopeType.ENVIRONMENT, env.getEnvironmentId(), true);
        spend(env, SEPTEMBER, 10, "40.00"); // $400 by the 10th of a 30-day month: forecast $1,200

        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(10))).isEqualTo(1);
        assertThat(titlesFor(owner, "BUDGET_FORECAST")).containsExactly("Budget forecast overrun: " + env.getDisplayName());
        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(10))).isZero();

        YearMonth october = SEPTEMBER.plusMonths(1);
        spend(env, october, 10, "40.00");
        assertThat(evaluation.evaluate(b, october, october.atDay(10))).isEqualTo(1);
        assertThat(titlesFor(owner, "BUDGET_FORECAST")).hasSize(2);
    }

    @Test
    void aTeamsOwnersHearEachAlertOnce() {
        Environment second = newEnvironment("Same team");
        grant(owner, AccessScopeType.ENVIRONMENT, second.getEnvironmentId(), AccessLevel.ADMIN);
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id IN (?, ?)",
                "{\"ownerTeam\":\"Payments\"}", env.getEnvironmentId(), second.getEnvironmentId());
        Budget b = budget(BudgetScopeType.TEAM, "Payments", false);
        spend(env, SEPTEMBER, 5, "60.00");
        spend(second, SEPTEMBER, 5, "50.00"); // $550 together

        assertThat(evaluation.evaluate(b, SEPTEMBER, SEPTEMBER.atDay(5))).isEqualTo(1);
        assertThat(titlesFor(owner, "BUDGET_THRESHOLD")).containsExactly("Budget 50% used: Team Payments");
        assertThat(jdbcTemplate.queryForObject("SELECT entity_type FROM notification WHERE user_id = ? AND type = 'BUDGET_THRESHOLD'",
                String.class, owner.getUserId())).isEqualTo("BUDGET");
    }
}
