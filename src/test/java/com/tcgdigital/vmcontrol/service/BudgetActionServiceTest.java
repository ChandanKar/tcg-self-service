package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetActionRequest;
import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.BudgetActionRequestRepository;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Budget actions (E20-T03): requested once a month when a budget is exhausted (only with
 * budgets.actions.enabled), approved by an owner other than the budget's creator (or an admin),
 * an approved freeze lasting until next month, and expiry of unapproved requests.
 */
class BudgetActionServiceTest extends AbstractIntegrationTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Autowired private BudgetActionService actions;
    @Autowired private BudgetEvaluationService evaluation;
    @Autowired private BudgetRepository budgets;
    @Autowired private BudgetActionRequestRepository requests;
    @Autowired private EnvironmentRepository environments;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private User creator;
    private User coOwner;
    private User member;
    private Budget budget;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Actions");
        creator = newUser("budget-creator@example.com", false, false);
        coOwner = newUser("co-owner@example.com", false, false);
        member = newUser("member@example.com", false, false);
        grant(creator, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);
        grant(coOwner, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);
        grant(member, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        Budget b = new Budget();
        b.setBudgetId(UUID.randomUUID().toString());
        b.setScopeType(BudgetScopeType.ENVIRONMENT);
        b.setScopeId(env.getEnvironmentId());
        b.setMonthlyAmount(new BigDecimal("100.00"));
        b.setForecastAlert(false);
        b.setActionType(BudgetActionType.FREEZE_STARTS);
        b.setCreatedByUserId(creator.getUserId());
        budget = budgets.saveAndFlush(b);
    }

    @AfterEach
    void actionsOff() {
        setActionsEnabled(false);
    }

    private void setActionsEnabled(boolean enabled) {
        Object target = AopTestUtils.getTargetObject(evaluation);
        ReflectionTestUtils.setField(target, "actionsEnabled", enabled);
    }

    private void spent(String amount) {
        jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), env.getEnvironmentId(),
                java.sql.Date.valueOf(SEPTEMBER.atDay(1)), new BigDecimal(amount));
    }

    private String raised() {
        assertThat(actions.raise(budget, SEPTEMBER)).isTrue();
        return requests.findByBudgetBudgetIdAndBudgetMonthAndActionType(budget.getBudgetId(), "2026-09",
                BudgetActionType.FREEZE_STARTS).orElseThrow().getRequestId();
    }

    @Test
    void withActionsOffAnExhaustedBudgetOnlyAlerts() {
        spent("150.00");
        evaluation.evaluate(budget, SEPTEMBER, SEPTEMBER.atDay(1));
        assertThat(requests.findAll()).isEmpty();
    }

    @Test
    void withActionsOnTheActionIsRequestedOnceAndNothingIsFrozen() {
        setActionsEnabled(true);
        spent("150.00");
        evaluation.evaluate(budget, SEPTEMBER, SEPTEMBER.atDay(1));
        evaluation.evaluate(budget, SEPTEMBER, SEPTEMBER.atDay(1));

        assertThat(requests.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(BudgetActionRequest.Status.PENDING));
        assertThat(environments.findById(env.getEnvironmentId()).orElseThrow().getStartFrozenUntil()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'BUDGET_ACTION_PENDING'",
                Integer.class, coOwner.getUserId())).isEqualTo(1);
        assertThat(actions.raise(budget, SEPTEMBER)).isFalse();
    }

    @Test
    void theCreatorCannotApproveTheirOwnBudgetsActionButAnotherOwnerCan() {
        String id = raised();
        assertThatThrownBy(() -> actions.approve(id, creator.getUserId(), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("You set this budget; another owner or an admin must approve its action");
        assertThatThrownBy(() -> actions.approve(id, member.getUserId(), null))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(actions.approve(id, coOwner.getUserId(), "over budget").status()).isEqualTo(BudgetActionRequest.Status.APPROVED);

        // Frozen until the first instant of October (cost day zone UTC).
        assertThat(environments.findById(env.getEnvironmentId()).orElseThrow().getStartFrozenUntil().toInstant())
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'BUDGET_FREEZE'",
                Integer.class, member.getUserId())).isEqualTo(1);
        assertThatThrownBy(() -> actions.approve(id, coOwner.getUserId(), null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("This budget action was already approved");
    }

    @Test
    void anAdminMayApproveTheirOwnAndRejectionChangesNothing() {
        User admin = newUser("budget-admin@example.com", true, false);
        budget.setCreatedByUserId(admin.getUserId());
        budgets.saveAndFlush(budget);
        String id = raised();
        assertThat(actions.reject(id, coOwner.getUserId(), "release week").status()).isEqualTo(BudgetActionRequest.Status.REJECTED);
        assertThat(environments.findById(env.getEnvironmentId()).orElseThrow().getStartFrozenUntil()).isNull();
        assertThat(actions.list(member.getUserId(), null)).isEmpty();
        assertThat(actions.list(admin.getUserId(), BudgetActionRequest.Status.REJECTED)).singleElement()
                .satisfies(r -> assertThat(r.decisionReason()).isEqualTo("release week"));
    }

    @Test
    void unapprovedRequestsExpireAtMonthEndAndAFreezeCanBeLifted() {
        String id = raised();
        assertThat(actions.expireBefore(SEPTEMBER)).isZero();
        assertThat(actions.expireBefore(SEPTEMBER.plusMonths(1))).isEqualTo(1);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(BudgetActionRequest.Status.EXPIRED);

        jdbcTemplate.update("UPDATE environment SET start_frozen_until = ? WHERE environment_id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)), env.getEnvironmentId());
        assertThatThrownBy(() -> actions.liftFreeze(env.getEnvironmentId(), member.getUserId(), null))
                .isInstanceOf(UnauthorizedException.class);
        actions.liftFreeze(env.getEnvironmentId(), coOwner.getUserId(), "budget raised");
        assertThat(environments.findById(env.getEnvironmentId()).orElseThrow().getStartFrozenUntil()).isNull();
        assertThatThrownBy(() -> actions.liftFreeze(env.getEnvironmentId(), coOwner.getUserId(), null))
                .hasMessage("Starts are not frozen in this environment");
    }
}
