package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleRequestDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleWindowDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Schedule rules with several time windows (E19-T01): each window fires on its own days and
 * remembers its own fired-on dates, windows on the same day must not overlap, the legacy
 * single-window payload still works, and V48 copies existing rules into one window each.
 * Not @Transactional: schedule evaluation runs its own transactions.
 */
class AutomationRuleWindowTest extends AbstractIntegrationTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = FRIDAY.plusDays(1);
    private static final LocalDate SUNDAY = FRIDAY.plusDays(2);

    @Autowired private AutomationRuleService automationRuleService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private User creator;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Windows");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        creator = newUser("window-creator-" + UUID.randomUUID() + "@example.com", false, true);
        grant(creator, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(awsCloudProviderService.stopVm(anyString(), org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class), anyBoolean(), any())).thenReturn(
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

    private void tickAt(LocalDate day, int hour, int minute) {
        setClock(Clock.fixed(LocalDateTime.of(day, LocalTime.of(hour, minute, 20)).atZone(KOLKATA).toInstant(), KOLKATA));
        automationRuleService.evaluateSchedules();
    }

    private static AutomationRuleWindowDTO window(String days, String start, String stop) {
        return new AutomationRuleWindowDTO(Arrays.asList(days.split(",")), start, stop);
    }

    private AutomationRuleRequestDTO request(AutomationRuleWindowDTO... windows) {
        AutomationRuleRequestDTO dto = new AutomationRuleRequestDTO();
        dto.setEnvironmentId(env.getEnvironmentId());
        dto.setName("Windows " + UUID.randomUUID().toString().substring(0, 6));
        dto.setScopeType(AutomationScopeType.ENVIRONMENT);
        dto.setTriggerType(AutomationTriggerType.SCHEDULE);
        dto.setWindows(new ArrayList<>(List.of(windows)));
        dto.setTimezone("Asia/Kolkata");
        return dto;
    }

    private List<Map<String, Object>> windowRows(String ruleId) {
        return jdbcTemplate.queryForList("SELECT * FROM automation_rule_window WHERE rule_id = ? ORDER BY position", ruleId);
    }

    /** Waits for the last (async) operation to finish, then puts the VM back to RUNNING. */
    private void runningAgain() {
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_execution "
                + "WHERE environment_id = ? AND status IN ('pending', 'in_progress')", Integer.class,
                env.getEnvironmentId())).isZero());
        jdbcTemplate.update("UPDATE vm SET status = 'RUNNING' WHERE group_id IN "
                + "(SELECT group_id FROM vm_group WHERE environment_id = ?)", env.getEnvironmentId());
    }

    private long runs(OperationType type) {
        return executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()).stream()
                .filter(e -> e.getOperationType() == type).count();
    }

    @Test
    void twoWindowsFireIndependentlyAndNotOnUnselectedDays() {
        AutomationRuleDTO rule = automationRuleService.createRule(creator.getUserId(), request(
                window("MON,TUE,WED,THU,FRI", "08:00", "20:00"), window("SAT", "10:00", "14:00")));
        assertThat(rule.getScheduleSummary()).isEqualTo("Mon-Fri 08:00-20:00; Sat 10:00-14:00");

        tickAt(FRIDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isEqualTo(1);
        assertThat(windowRows(rule.getRuleId()).get(0).get("last_stop_fired_on")).isEqualTo(Date.valueOf(FRIDAY));
        assertThat(windowRows(rule.getRuleId()).get(1).get("last_stop_fired_on")).isNull();

        // Saturday: the weekday window is silent; the Saturday window starts at 10:00 (the VM is
        // already running, so nothing to do) and stops at 14:00.
        runningAgain();
        tickAt(SATURDAY, 10, 0);
        assertThat(windowRows(rule.getRuleId()).get(1).get("last_start_fired_on")).isEqualTo(Date.valueOf(SATURDAY));
        tickAt(SATURDAY, 14, 0);
        assertThat(windowRows(rule.getRuleId()).get(1).get("last_stop_fired_on")).isEqualTo(Date.valueOf(SATURDAY));
        assertThat(runs(OperationType.STOP)).isEqualTo(2);
        assertThat(windowRows(rule.getRuleId()).get(0).get("last_start_fired_on")).isNull();

        // Sunday: no window.
        runningAgain();
        tickAt(SUNDAY, 14, 0);
        tickAt(SUNDAY, 20, 0);
        assertThat(runs(OperationType.STOP)).isEqualTo(2);
    }

    @Test
    void savingTheRuleKeepsEachWindowsFiredOnDates() {
        AutomationRuleDTO rule = automationRuleService.createRule(creator.getUserId(), request(
                window("MON,TUE,WED,THU,FRI", null, "20:00"), window("SAT", null, "14:00")));
        tickAt(FRIDAY, 20, 0);

        automationRuleService.updateRule(rule.getRuleId(), creator.getUserId(), request(
                window("MON,TUE,WED,THU,FRI", null, "20:00"), window("SAT", null, "14:00")));
        assertThat(windowRows(rule.getRuleId()).get(0).get("last_stop_fired_on")).isEqualTo(Date.valueOf(FRIDAY));
        tickAt(FRIDAY, 20, 5); // saved inside the catch-up window: no second stop
        assertThat(runs(OperationType.STOP)).isEqualTo(1);

        AutomationRuleDTO shrunk = automationRuleService.updateRule(rule.getRuleId(), creator.getUserId(),
                request(window("MON,TUE,WED,THU,FRI", null, "20:00")));
        assertThat(shrunk.getWindows()).hasSize(1);
        assertThat(windowRows(rule.getRuleId())).hasSize(1);
    }

    @Test
    void overlappingWindowsOnTheSameDayAreRejected() {
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(), request(
                window("MON,TUE,WED,THU,FRI", "08:00", "20:00"), window("MON", "19:00", "21:00"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Windows overlap on MON");
        // A stop-only window inside another window's hours overlaps too.
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(), request(
                window("TUE", "08:00", "20:00"), window("TUE", null, "12:00"))))
                .hasMessage("Windows overlap on TUE");
        // Back to back, or on different days, is fine.
        assertThat(automationRuleService.createRule(creator.getUserId(), request(
                window("MON", "08:00", "12:00"), window("MON", "12:00", "18:00"), window("SAT", "08:00", "20:00")))
                .getWindows()).hasSize(3);
    }

    @Test
    void eachWindowIsValidatedAndAtMostSevenAreAllowed() {
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(), request(
                window("MON", "08:00", "20:00"), window("TUE", "25:00", "20:00"))))
                .hasMessage("Window 2: Start time must be in HH:mm format");
        AutomationRuleWindowDTO[] eight = new AutomationRuleWindowDTO[8];
        for (int i = 0; i < 8; i++) {
            eight[i] = window("MON", String.format("%02d:00", i), null);
        }
        assertThatThrownBy(() -> automationRuleService.createRule(creator.getUserId(), request(eight)))
                .hasMessage("A schedule rule can have at most 7 windows");
    }

    @Test
    void aLegacyPayloadCreatesOneWindow() {
        AutomationRuleRequestDTO legacy = request();
        legacy.setWindows(null);
        legacy.setDaysOfWeek(List.of("MON", "TUE", "WED", "THU", "FRI"));
        legacy.setStartTime("08:00");
        legacy.setStopTime("20:00");

        AutomationRuleDTO rule = automationRuleService.createRule(creator.getUserId(), legacy);

        assertThat(rule.getWindows()).singleElement().satisfies(w -> {
            assertThat(w.getDaysOfWeek()).containsExactly("MON", "TUE", "WED", "THU", "FRI");
            assertThat(w.getStartTime()).isEqualTo("08:00");
            assertThat(w.getStopTime()).isEqualTo("20:00");
        });
        // Older readers still see the single-window fields.
        assertThat(rule.getDaysOfWeek()).containsExactly("MON", "TUE", "WED", "THU", "FRI");
        assertThat(rule.getStopTime()).isEqualTo("20:00");
        assertThat(windowRows(rule.getRuleId())).hasSize(1);
    }

    @Test
    void theMigrationCopiesALegacyRuleIntoOneWindowOnce() throws Exception {
        String ruleId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO automation_rule (rule_id, name, environment_id, scope_type, trigger_type, "
                        + "days_of_week, stop_time, start_time, timezone, skip_if_already_in_target_state, enabled, "
                        + "created_by_user_id, created_at, updated_at, last_stop_fired_on) "
                        + "VALUES (?, 'Legacy', ?, 'ENVIRONMENT', 'SCHEDULE', 'MON,WED', '20:00', '08:00', 'Asia/Kolkata', "
                        + "TRUE, TRUE, ?, NOW(), NOW(), ?)",
                ruleId, env.getEnvironmentId(), creator.getUserId(), Date.valueOf(FRIDAY));
        String accessRuleId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO automation_rule (rule_id, name, environment_id, scope_type, trigger_type, "
                        + "access_grant_mode, skip_if_already_in_target_state, enabled, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, 'Access', ?, 'ENVIRONMENT', 'ACCESS_GRANT', 'LOCK_ACQUIRE', TRUE, TRUE, ?, NOW(), NOW())",
                accessRuleId, env.getEnvironmentId(), creator.getUserId());

        String sql = new String(new ClassPathResource("db/migration/V48__automation_rule_windows.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        String copy = Arrays.stream(sql.split(";")).map(String::trim)
                .filter(s -> s.startsWith("INSERT INTO automation_rule_window")).findFirst().orElseThrow();
        jdbcTemplate.update(copy);
        jdbcTemplate.update(copy); // re-running adds nothing

        List<Map<String, Object>> rows = windowRows(ruleId);
        assertThat(rows).singleElement().satisfies(w -> {
            assertThat(w.get("days_of_week")).isEqualTo("MON,WED");
            assertThat(w.get("start_time")).isEqualTo("08:00");
            assertThat(w.get("stop_time")).isEqualTo("20:00");
            assertThat(w.get("last_stop_fired_on")).isEqualTo(Date.valueOf(FRIDAY));
        });
        assertThat(windowRows(accessRuleId)).isEmpty();
        assertThat(automationRuleService.getRule(ruleId).getScheduleSummary()).isEqualTo("Mon, Wed 08:00-20:00");
    }
}
