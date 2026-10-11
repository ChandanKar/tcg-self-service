package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRunStatus;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Schedule rules fire end to end from a scheduler thread (no web session), catch up a late tick,
 * retry while the environment is locked and record a missed window (E06-T03, C6, H26).
 * A fixed clock drives "now"; not @Transactional.
 */
class AutomationRuleScheduleIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);

    @Autowired private AutomationRuleService automationRuleService;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private LockService lockService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private HolidayCalendarService holidayCalendars;

    private Environment env;
    private User owner;
    private AutomationRule rule;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Schedule");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        owner = newUser("schedule-owner@example.com", false, true);
        grant(owner, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(awsCloudProviderService.stopVm(anyString(), org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));

        AutomationRule r = new AutomationRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setName("Stop at eight");
        r.setEnvironment(env);
        r.setScopeType(AutomationScopeType.ENVIRONMENT);
        r.setTriggerType(AutomationTriggerType.SCHEDULE);
        r.addWindow("MON,TUE,WED,THU,FRI", null, "20:00");
        r.setTimezone("Asia/Kolkata");
        r.setSkipIfAlreadyInTargetState(true);
        r.setEnabled(true);
        r.setCreatedByUserId(owner.getUserId());
        rule = rules.saveAndFlush(r);
        // The rule existed long before the times under test.
        jdbcTemplate.update("UPDATE automation_rule SET created_at = ? WHERE rule_id = ?",
                Timestamp.valueOf("2026-01-01 00:00:00"), rule.getRuleId());
    }

    @AfterEach
    void realClock() {
        setClock(Clock.systemDefaultZone());
    }

    private void setClock(Clock clock) {
        Object target = AopTestUtils.getTargetObject(automationRuleService);
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    private void tickAt(int hour, int minute, int second) {
        setClock(Clock.fixed(LocalDateTime.of(WEDNESDAY, java.time.LocalTime.of(hour, minute, second))
                .atZone(KOLKATA).toInstant(), KOLKATA));
        automationRuleService.evaluateSchedules(); // as the scheduler thread calls it: no session
    }

    private AutomationRule reload() {
        return rules.findById(rule.getRuleId()).orElseThrow();
    }

    /** The rule's (only) window's stop fired-on date; windows are lazy outside a transaction. */
    private Date lastStopFiredOn() {
        return jdbcTemplate.queryForObject("SELECT last_stop_fired_on FROM automation_rule_window WHERE rule_id = ?",
                Date.class, rule.getRuleId());
    }

    private long stopRuns() {
        return executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()).stream()
                .filter(e -> e.getOperationType() == OperationType.STOP).count();
    }

    @Test
    void firesOnTheTickAtItsMinuteWithoutAWebSession() {
        tickAt(20, 0, 40);

        assertThat(stopRuns()).isEqualTo(1);
        AutomationRule after = reload();
        assertThat(after.getLastRunStatus()).isEqualTo(AutomationRunStatus.SUCCESS);
        assertThat(lastStopFiredOn()).isEqualTo(Date.valueOf(WEDNESDAY));

        tickAt(20, 1, 40); // the next tick does not fire again
        assertThat(stopRuns()).isEqualTo(1);
    }

    @Test
    void aLateTickInsideTheWindowStillFires() {
        tickAt(20, 3, 0);

        assertThat(stopRuns()).isEqualTo(1);
    }

    @Test
    void aTickAfterTheWindowRecordsAMissedRunAndDoesNotFire() {
        tickAt(20, 20, 0);

        assertThat(stopRuns()).isZero();
        AutomationRule after = reload();
        assertThat(after.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        assertThat(after.getLastRunDetail()).isEqualTo("Missed window: the scheduler did not run at 20:00");
        assertThat(lastStopFiredOn()).isEqualTo(Date.valueOf(WEDNESDAY));
    }

    @Test
    void lockedAtTheTimeThenFreeFiresOnALaterTickInTheWindow() {
        User holder = newUser("holder@example.com", false, false);
        grant(holder, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "hands off", null);

        tickAt(20, 0, 10);
        AutomationRule locked = reload();
        assertThat(locked.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        assertThat(locked.getLastRunDetail()).startsWith("Environment locked by");
        assertThat(lastStopFiredOn()).isNull(); // retried on the next tick
        tickAt(20, 1, 10); // still locked: the same skip is not audited again
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("AUTOMATION_RULE_SKIPPED", PageRequest.of(0, 20))
                .getContent()).filteredOn(a -> rule.getRuleId().equals(a.getTargetId())).hasSize(1);

        lockService.releaseLock(env.getEnvironmentId(), holder.getUserId());
        tickAt(20, 5, 10);

        assertThat(stopRuns()).isEqualTo(1);
        assertThat(lastStopFiredOn()).isEqualTo(Date.valueOf(WEDNESDAY));
        assertThat(reload().getLastRunStatus()).isEqualTo(AutomationRunStatus.SUCCESS);
    }

    /** Links the rule to a calendar holding Wednesday, and gives its window an 08:00 start (E19-T02). */
    private void holidayOnWednesday(String behavior) {
        String calendarId = holidayCalendars.create(owner.getUserId(),
                new com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarRequest("India " + UUID.randomUUID(), null)).calendarId();
        holidayCalendars.addDate(calendarId, owner.getUserId(),
                new com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateRequest(WEDNESDAY, "Gandhi Jayanti"));
        jdbcTemplate.update("UPDATE automation_rule SET holiday_calendar_id = ?, holiday_behavior = ? WHERE rule_id = ?",
                calendarId, behavior, rule.getRuleId());
        jdbcTemplate.update("UPDATE automation_rule_window SET start_time = '08:00' WHERE rule_id = ?", rule.getRuleId());
    }

    private long startRuns() {
        return executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()).stream()
                .filter(e -> e.getOperationType() == OperationType.START).count();
    }

    @Test
    void aHolidaySkipsTheStartButTheStopStillRuns() {
        holidayOnWednesday("SKIP_START");

        tickAt(8, 0, 10);
        AutomationRule skipped = reload();
        assertThat(startRuns()).isZero();
        assertThat(skipped.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        assertThat(skipped.getLastRunReason()).isEqualTo(com.tcgdigital.vmcontrol.model.AutomationRunReason.HOLIDAY);
        assertThat(skipped.getLastRunDetail()).isEqualTo("Skipped: holiday (Gandhi Jayanti)");
        assertThat(jdbcTemplate.queryForObject("SELECT last_start_fired_on FROM automation_rule_window WHERE rule_id = ?",
                Date.class, rule.getRuleId())).isEqualTo(Date.valueOf(WEDNESDAY));

        tickAt(20, 0, 10);
        assertThat(stopRuns()).isEqualTo(1);
        assertThat(reload().getLastRunStatus()).isEqualTo(AutomationRunStatus.SUCCESS);
    }

    @Test
    void skipAllSkipsTheStopTooAndIsNotReportedAsMissed() {
        holidayOnWednesday("SKIP_ALL");

        tickAt(20, 0, 10);
        assertThat(stopRuns()).isZero();
        assertThat(reload().getLastRunDetail()).isEqualTo("Skipped: holiday (Gandhi Jayanti)");
        assertThat(lastStopFiredOn()).isEqualTo(Date.valueOf(WEDNESDAY));

        // A tick after the window (the scheduler was down at 20:00) is a holiday skip too, not a miss.
        jdbcTemplate.update("UPDATE automation_rule_window SET last_stop_fired_on = NULL WHERE rule_id = ?", rule.getRuleId());
        tickAt(21, 0, 0);
        assertThat(reload().getLastRunReason()).isEqualTo(com.tcgdigital.vmcontrol.model.AutomationRunReason.HOLIDAY);
        assertThat(stopRuns()).isZero();
    }

    @Test
    void lockedForTheWholeWindowEndsAsMissedWithTheReason() {
        User holder = newUser("holder2@example.com", false, false);
        grant(holder, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "hands off", null);

        tickAt(20, 0, 10);
        tickAt(20, 16, 0);

        AutomationRule after = reload();
        assertThat(after.getLastRunDetail()).startsWith("Missed window: Environment locked by");
        assertThat(lastStopFiredOn()).isEqualTo(Date.valueOf(WEDNESDAY));
        assertThat(stopRuns()).isZero();
    }
}
