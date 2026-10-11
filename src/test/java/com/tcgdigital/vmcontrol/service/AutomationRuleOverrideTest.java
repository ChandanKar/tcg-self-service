package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleOverrideDTOs.OverrideDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleOverrideDTOs.OverrideRequest;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRuleOverride.SkipAction;
import com.tcgdigital.vmcontrol.model.AutomationRunReason;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Per-rule overrides (E19-T03): an override covering a due time skips that action (and only the
 * actions it names), is reported with its reason and author, and expires on its own; bad ranges
 * are refused. A fixed clock drives "now"; not @Transactional.
 */
class AutomationRuleOverrideTest extends AbstractIntegrationTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);

    @Autowired private AutomationRuleService automationRuleService;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private User owner;
    private User priya;
    private AutomationRule rule;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Override");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        owner = newUser("override-owner@example.com", false, true);
        priya = newUser("Priya@example.com", false, false);
        grant(owner, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(awsCloudProviderService.stopVm(anyString(), org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));

        AutomationRule r = new AutomationRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setName("Stop at eight");
        r.setEnvironment(env);
        r.setScopeType(AutomationScopeType.ENVIRONMENT);
        r.setTriggerType(AutomationTriggerType.SCHEDULE);
        r.addWindow("MON,TUE,WED,THU,FRI", "08:00", "20:00");
        r.setTimezone("Asia/Kolkata");
        r.setSkipIfAlreadyInTargetState(true);
        r.setEnabled(true);
        r.setCreatedByUserId(owner.getUserId());
        rule = rules.saveAndFlush(r);
        jdbcTemplate.update("UPDATE automation_rule SET created_at = ? WHERE rule_id = ?",
                Timestamp.valueOf("2026-01-01 00:00:00"), rule.getRuleId());
        setClockAt(WEDNESDAY, 10, 0);
    }

    @AfterEach
    void realClock() {
        setClock(Clock.systemDefaultZone());
    }

    private void setClock(Clock clock) {
        Object target = AopTestUtils.getTargetObject(automationRuleService);
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    private void setClockAt(LocalDate day, int hour, int minute) {
        setClock(Clock.fixed(at(day, hour, minute).plusSeconds(20), KOLKATA));
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(KOLKATA).toInstant();
    }

    private void tickAt(LocalDate day, int hour, int minute) {
        setClockAt(day, hour, minute);
        automationRuleService.evaluateSchedules();
    }

    /** Marks the 08:00 start as done for the day, so a 20:00 tick does not also report it missed. */
    private void startAlreadyRan(LocalDate day) {
        jdbcTemplate.update("UPDATE automation_rule_window SET last_start_fired_on = ? WHERE rule_id = ?",
                java.sql.Date.valueOf(day), rule.getRuleId());
    }

    private long runs(OperationType type) {
        return executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()).stream()
                .filter(e -> e.getOperationType() == type).count();
    }

    private AutomationRule reload() {
        return rules.findById(rule.getRuleId()).orElseThrow();
    }

    private OverrideDTO override(SkipAction action, Instant from, Instant to, String reason) {
        return automationRuleService.createOverride(rule.getRuleId(), priya.getUserId(),
                new OverrideRequest(from, to, action, reason));
    }

    @Test
    void aStopOverrideSkipsTonightsStopAndTomorrowsRunsNormally() {
        OverrideDTO created = override(SkipAction.STOP, at(WEDNESDAY, 19, 0), at(WEDNESDAY, 23, 0), "release");
        assertThat(created.active()).isFalse();
        assertThat(automationRuleService.getRule(rule.getRuleId()).getOverrideCount()).isEqualTo(1);

        startAlreadyRan(WEDNESDAY);
        tickAt(WEDNESDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isZero();
        AutomationRule skipped = reload();
        assertThat(skipped.getLastRunReason()).isEqualTo(AutomationRunReason.OVERRIDE);
        assertThat(skipped.getLastRunDetail()).isEqualTo("Skipped by override: release (Priya)");

        startAlreadyRan(WEDNESDAY.plusDays(1));
        tickAt(WEDNESDAY.plusDays(1), 20, 0);
        assertThat(runs(OperationType.STOP)).isEqualTo(1);
        assertThat(automationRuleService.getRule(rule.getRuleId()).getOverrideCount()).isZero(); // expired
    }

    @Test
    void anOverrideSkipsOnlyTheActionsItNames() {
        override(SkipAction.START, at(WEDNESDAY, 0, 0), at(WEDNESDAY, 23, 59), "keep it off");

        // At 20:00 the stop is not covered and runs; the 08:00 start (evaluated after it, as a
        // missed time) is covered and reported as skipped by the override, not as missed.
        tickAt(WEDNESDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isEqualTo(1);
        assertThat(reload().getLastRunReason()).isEqualTo(AutomationRunReason.OVERRIDE);
        assertThat(reload().getLastRunDetail()).isEqualTo("Skipped by override: keep it off (Priya)");
    }

    @Test
    void anAllOverrideSkipsStartsAndStops() {
        setClockAt(WEDNESDAY, 7, 0);
        override(SkipAction.ALL, at(WEDNESDAY, 7, 30), at(WEDNESDAY, 21, 0), "freeze");

        tickAt(WEDNESDAY, 8, 0);
        assertThat(reload().getLastRunDetail()).isEqualTo("Skipped by override: freeze (Priya)");
        tickAt(WEDNESDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isZero();
        assertThat(runs(OperationType.START)).isZero();
        assertThat(automationRuleService.listOverrides(rule.getRuleId())).singleElement()
                .satisfies(o -> assertThat(o.active()).isTrue());
    }

    @Test
    void badRangesAreRefused() {
        assertThatThrownBy(() -> override(SkipAction.STOP, at(WEDNESDAY, 23, 0), at(WEDNESDAY, 19, 0), "backwards"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The override must end after it starts");
        assertThatThrownBy(() -> override(SkipAction.STOP, at(WEDNESDAY, 12, 0),
                at(WEDNESDAY, 12, 0).plus(Duration.ofDays(31)), "too long"))
                .hasMessage("An override can last at most 30 days");
        assertThatThrownBy(() -> override(SkipAction.STOP, at(WEDNESDAY, 1, 0), at(WEDNESDAY, 2, 0), "past"))
                .hasMessage("The override has already ended");
    }

    @Test
    void removingAnOverrideLetsTheRuleRunAgain() {
        OverrideDTO created = override(SkipAction.STOP, at(WEDNESDAY, 19, 0), at(WEDNESDAY, 23, 0), "release");
        automationRuleService.deleteOverride(rule.getRuleId(), created.overrideId(), priya.getUserId());

        startAlreadyRan(WEDNESDAY);
        tickAt(WEDNESDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isEqualTo(1);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = ? "
                + "AND action_type = 'AUTOMATION_RULE_UPDATED' AND details LIKE 'Override%'", Integer.class,
                rule.getRuleId())).isEqualTo(2));
    }
}
