package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleRequestDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRunReason;
import com.tcgdigital.vmcontrol.model.AutomationRunStatus;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Rules switch themselves off when they can no longer act, validation of days and times, the run
 * reason and the bounded run detail (E06-T04, M16, LOW-AUTO-*), and the rules list with environments
 * fetched up front (PL-07). Not @Transactional: schedule evaluation runs its own transactions.
 */
class AutomationRuleServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);

    @Autowired private AutomationRuleService automationRuleService;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private VmGroup group;
    private Vm vm;
    private User creator;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Auto");
        group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.RUNNING);
        creator = newUser("rule-creator-" + UUID.randomUUID() + "@example.com", false, true);
        grant(creator, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
    }

    @AfterEach
    void realClock() {
        setClock(Clock.systemDefaultZone());
    }

    private void setClock(Clock clock) {
        Object target = AopTestUtils.getTargetObject(automationRuleService);
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    private AutomationRule scheduleRule(AutomationScopeType scope, String scopeId) {
        AutomationRule r = new AutomationRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setName("Stop at eight " + UUID.randomUUID().toString().substring(0, 6));
        r.setEnvironment(env);
        r.setScopeType(scope);
        r.setScopeId(scopeId);
        r.setTriggerType(AutomationTriggerType.SCHEDULE);
        r.setDaysOfWeek("MON,TUE,WED,THU,FRI");
        r.setStopTime("20:00");
        r.setTimezone("Asia/Kolkata");
        r.setSkipIfAlreadyInTargetState(true);
        r.setEnabled(true);
        r.setCreatedByUserId(creator.getUserId());
        AutomationRule saved = rules.saveAndFlush(r);
        jdbcTemplate.update("UPDATE automation_rule SET created_at = ? WHERE rule_id = ?",
                Timestamp.valueOf("2026-01-01 00:00:00"), saved.getRuleId());
        return saved;
    }

    private void tickAtEight() {
        setClock(Clock.fixed(LocalDateTime.of(WEDNESDAY, LocalTime.of(20, 0, 30)).atZone(KOLKATA).toInstant(), KOLKATA));
        automationRuleService.evaluateSchedules();
    }

    private AutomationRule reload(AutomationRule rule) {
        return rules.findById(rule.getRuleId()).orElseThrow();
    }

    private void assertDisabled(AutomationRule rule, AutomationRunReason reason, String message) {
        AutomationRule after = reload(rule);
        assertThat(after.getEnabled()).isFalse();
        assertThat(after.getDisabledReason()).isEqualTo(message);
        assertThat(after.getLastRunReason()).isEqualTo(reason);
        assertThat(after.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action_type = 'AUTOMATION_RULE_UPDATED'",
                Integer.class, rule.getRuleId());
        assertThat(audits).isEqualTo(1);
        assertThat(stopRuns()).isZero();
    }

    private long stopRuns() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_execution WHERE environment_id = ?", Integer.class, env.getEnvironmentId());
        return n == null ? 0 : n;
    }

    private int adminNotices(String ruleName) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE title = ?", Integer.class, "Automation rule disabled: " + ruleName);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ preflight (M16)

    @Test
    void aHealthyRuleStillFires() {
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);

        tickAtEight();

        AutomationRule after = reload(rule);
        assertThat(after.getEnabled()).isTrue();
        assertThat(after.getLastRunReason()).isEqualTo(AutomationRunReason.OK);
        assertThat(stopRuns()).isEqualTo(1);
    }

    @Test
    void deactivatedEnvironmentDisablesTheRule() {
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);
        jdbcTemplate.update("UPDATE environment SET is_active = FALSE WHERE environment_id = ?", env.getEnvironmentId());

        tickAtEight();

        assertDisabled(rule, AutomationRunReason.ENVIRONMENT_INACTIVE, "Environment deactivated");
        assertThat(adminNotices(rule.getName())).isZero();
    }

    @Test
    void inactiveCreatorDisablesTheRuleAndTellsAdmins() {
        newUser("active-admin-" + UUID.randomUUID() + "@example.com", true, false);
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);
        jdbcTemplate.update("UPDATE app_user SET is_active = FALSE WHERE user_id = ?", creator.getUserId());

        tickAtEight();

        assertDisabled(rule, AutomationRunReason.CREATOR_INACTIVE, "Rule creator is no longer active or no longer an admin");
        assertThat(adminNotices(rule.getName())).isPositive();
    }

    @Test
    void demotedCreatorDisablesTheRule() {
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);
        jdbcTemplate.update("UPDATE app_user SET env_admin = FALSE, admin = FALSE WHERE user_id = ?", creator.getUserId());

        tickAtEight();

        assertDisabled(rule, AutomationRunReason.CREATOR_INACTIVE, "Rule creator is no longer active or no longer an admin");
    }

    @Test
    void missingGroupDisablesTheRule() {
        AutomationRule rule = scheduleRule(AutomationScopeType.GROUP, UUID.randomUUID().toString());

        tickAtEight();

        assertDisabled(rule, AutomationRunReason.SCOPE_MISSING, "Target group no longer exists");
    }

    @Test
    void deletedVmDisablesTheRule() {
        AutomationRule rule = scheduleRule(AutomationScopeType.VM, vm.getVmId());
        jdbcTemplate.update("UPDATE vm SET is_active = FALSE WHERE vm_id = ?", vm.getVmId());

        tickAtEight();

        assertDisabled(rule, AutomationRunReason.SCOPE_MISSING, "Target VM no longer exists");
    }

    @Test
    void reEnablingIsRefusedWhileTheProblemHoldsAndClearsTheReasonAfter() {
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);
        jdbcTemplate.update("UPDATE environment SET is_active = FALSE WHERE environment_id = ?", env.getEnvironmentId());
        tickAtEight();

        assertThatThrownBy(() -> automationRuleService.setEnabled(rule.getRuleId(), true, creator.getUserId()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("This rule cannot be enabled: Environment deactivated");
        assertThat(reload(rule).getEnabled()).isFalse();

        jdbcTemplate.update("UPDATE environment SET is_active = TRUE WHERE environment_id = ?", env.getEnvironmentId());
        AutomationRuleDTO dto = automationRuleService.setEnabled(rule.getRuleId(), true, creator.getUserId());

        assertThat(dto.getDisabledReason()).isNull();
        assertThat(reload(rule).getEnabled()).isTrue();
        assertThat(reload(rule).getDisabledReason()).isNull();
    }

    // ------------------------------------------------------------------ validation (LOW-AUTO-*)

    private AutomationRuleRequestDTO request(List<String> days, String stop, String start) {
        AutomationRuleRequestDTO dto = new AutomationRuleRequestDTO();
        dto.setEnvironmentId(env.getEnvironmentId());
        dto.setName("Validated " + UUID.randomUUID().toString().substring(0, 6));
        dto.setScopeType(AutomationScopeType.ENVIRONMENT);
        dto.setTriggerType(AutomationTriggerType.SCHEDULE);
        dto.setDaysOfWeek(days);
        dto.setStopTime(stop);
        dto.setStartTime(start);
        dto.setTimezone("Asia/Kolkata");
        return dto;
    }

    @Test
    void unknownDayNamesAreRejected() {
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(),
                request(List.of("MON", "Funday"), "20:00", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Unknown day: Funday");
    }

    @Test
    void equalStartAndStopTimesAreRejected() {
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(),
                request(List.of("MON"), "08:00", "08:00")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Start and stop times must differ");
    }

    @Test
    void daysAreDeduplicatedAndStoredInWeekOrder() {
        AutomationRuleDTO dto = automationRuleService.createRule(creator.getUserId(),
                request(List.of("fri", "MON", " wed", "Mon"), "20:00", "08:00"));

        assertThat(rules.findById(dto.getRuleId()).orElseThrow().getDaysOfWeek()).isEqualTo("MON,WED,FRI");
    }

    // ------------------------------------------------------------------ run detail and list

    @Test
    void longRunDetailIsCutToTheColumn() {
        AutomationRule rule = scheduleRule(AutomationScopeType.ENVIRONMENT, null);
        Object target = AopTestUtils.getTargetObject(automationRuleService);

        ReflectionTestUtils.invokeMethod(target, "recordRun", rule, AutomationRunStatus.FAILED,
                AutomationRunReason.ERROR, "x".repeat(900));

        String detail = reload(rule).getLastRunDetail();
        assertThat(detail).hasSize(500).endsWith("...");
    }

    @Test
    void listReturnsRulesWithTheirEnvironmentNames() {
        AutomationRule rule = scheduleRule(AutomationScopeType.GROUP, group.getGroupId());

        List<AutomationRuleDTO> listed = automationRuleService.listRules(env.getEnvironmentId());

        assertThat(listed).extracting(AutomationRuleDTO::getRuleId).containsExactly(rule.getRuleId());
        assertThat(listed.get(0).getEnvironmentName()).isEqualTo(env.getName());
        assertThat(automationRuleService.listRules(null)).extracting(AutomationRuleDTO::getRuleId).contains(rule.getRuleId());
    }

    @Test
    void migrationV31AddedTheReasonColumns() {
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                "AND TABLE_NAME = 'automation_rule' AND COLUMN_NAME IN ('last_run_reason', 'disabled_reason')",
                Integer.class);
        assertThat(columns).isEqualTo(2);
    }
}
