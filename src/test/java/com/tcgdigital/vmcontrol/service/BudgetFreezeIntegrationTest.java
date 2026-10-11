package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
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
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A budget start freeze (E20-T03): a user's start is refused with the reason, a stop still runs,
 * an admin's start goes through and is audited as an override, and a schedule rule's start is
 * skipped with the freeze reason.
 */
class BudgetFreezeIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operations;
    @Autowired private LockService lockService;
    @Autowired private AutomationRuleService automationRuleService;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private User user;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Frozen");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        user = newUser("frozen-user@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        jdbcTemplate.update("UPDATE environment SET start_frozen_until = ? WHERE environment_id = ?",
                Timestamp.from(Instant.now().plusSeconds(10 * 86_400)), env.getEnvironmentId());
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
    }

    @AfterEach
    void realClock() {
        Object target = AopTestUtils.getTargetObject(automationRuleService);
        ReflectionTestUtils.setField(target, "clock", Clock.systemDefaultZone());
    }

    private static StartOperationDTO op(OperationType type) {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(type);
        dto.setSkipAlreadyInTargetState(true);
        return dto;
    }

    @Test
    void aUsersStartIsRefusedButAStopRuns() {
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "work", null);

        assertThatThrownBy(() -> operations.startOperationIfNeeded(env.getEnvironmentId(), user.getUserId(), op(OperationType.START)))
                .isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("Starts are frozen until ")
                .hasMessageEndingWith("because the environment's budget is exhausted. Ask an owner to lift the freeze.");

        assertThat(operations.startOperationIfNeeded(env.getEnvironmentId(), user.getUserId(), op(OperationType.STOP)))
                .isPresent();
    }

    @Test
    void anAdminsStartGoesThroughAndIsAudited() {
        User admin = newUser("freeze-admin@example.com", true, false);
        lockService.acquireLock(env.getEnvironmentId(), admin.getUserId(), "fix", null);

        // The VM is already running, so there is nothing to start, but the freeze let it through.
        assertThat(operations.startOperationIfNeeded(env.getEnvironmentId(), admin.getUserId(), op(OperationType.START))).isEmpty();
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = "
                + "'BUDGET_FREEZE_OVERRIDDEN' AND environment_id = ?", Integer.class, env.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void aScheduledStartIsSkippedWithTheFreezeReason() {
        User owner = newUser("freeze-rule-owner@example.com", true, false); // even an admin's rule is held
        AutomationRule r = new AutomationRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setName("Morning start");
        r.setEnvironment(env);
        r.setScopeType(AutomationScopeType.ENVIRONMENT);
        r.setTriggerType(AutomationTriggerType.SCHEDULE);
        r.addWindow("MON,TUE,WED,THU,FRI,SAT,SUN", "08:00", null);
        r.setTimezone("UTC");
        r.setEnabled(true);
        r.setCreatedByUserId(owner.getUserId());
        AutomationRule rule = rules.saveAndFlush(r);
        jdbcTemplate.update("UPDATE automation_rule SET created_at = ? WHERE rule_id = ?",
                Timestamp.valueOf("2026-01-01 00:00:00"), rule.getRuleId());

        ZonedDateTime eight = ZonedDateTime.now(ZoneId.of("UTC")).with(LocalTime.of(8, 0, 20));
        Object target = AopTestUtils.getTargetObject(automationRuleService);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(eight.toInstant(), ZoneId.of("UTC")));
        automationRuleService.evaluateSchedules();

        AutomationRule after = rules.findById(rule.getRuleId()).orElseThrow();
        assertThat(after.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        assertThat(after.getLastRunDetail()).startsWith("Starts are frozen until ");
    }
}
