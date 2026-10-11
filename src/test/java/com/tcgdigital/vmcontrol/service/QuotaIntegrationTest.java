package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team quotas end to end (E25-T07): month-to-date usage, BLOCK (409), REQUIRE_APPROVAL (202),
 * WARN (runs with a warning header), the quota API and once-a-month warnings.
 */
class QuotaIntegrationTest extends SecuredWebTestBase {

    @Autowired private QuotaService quotaService;
    @Autowired private QuotaUsageService usageService;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private VmStateHistoryRepository history;
    @Autowired private CostDailySnapshotRepository snapshots;
    @Autowired private NotificationRepository notifications;
    @Autowired private JdbcTemplate jdbc;

    private Team team;
    private Environment env;
    private Vm vm;

    private Object quotaTarget() {
        Object target = AopTestUtils.getTargetObject(quotaService);
        return target;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quotaTarget(), "enabled", true);
        team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(TeamService.slug("Quota team"));
        team.setDisplayName("Quota team");
        team = teams.saveAndFlush(team);
        env = newEnvironment("Quota env");
        env.setTeam(team);
        env = environmentRepository.saveAndFlush(env);
        VmGroup group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.STOPPED);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        members.saveAndFlush(new TeamMember(team.getTeamId(), operator.getUserId(), TeamMember.Source.MANUAL, new Timestamp(System.currentTimeMillis())));
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(quotaTarget(), "enabled", false);
    }

    private void quota(String json) throws Exception {
        mockMvc.perform(put("/api/v1/teams/" + team.getTeamId() + "/quotas").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions start() throws Exception {
        return mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/operations").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content("{\"operationType\":\"START\",\"skipAlreadyInTargetState\":false}"));
    }

    private void transition(VmStatus from, VmStatus to, Instant at) {
        VmStateHistory h = new VmStateHistory();
        h.setHistoryId(UUID.randomUUID().toString());
        h.setVm(vm);
        h.setPreviousStatus(from);
        h.setNewStatus(to);
        h.setChangeSource("TEST");
        history.saveAndFlush(h);
        // changed_at is a creation timestamp: backdate it directly.
        jdbc.update("UPDATE vm_state_history SET created_at = ? WHERE history_id = ?", Timestamp.from(at), h.getHistoryId());
    }

    @Test
    void monthToDateCountsOnlyThisMonthsRuntimeAndCost() {
        Instant monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbc.update("UPDATE vm SET created_at = ? WHERE vm_id = ?", Timestamp.from(monthStart.minus(Duration.ofDays(5))), vm.getVmId());
        transition(VmStatus.STOPPED, VmStatus.RUNNING, monthStart.minus(Duration.ofDays(2)));
        transition(VmStatus.RUNNING, VmStatus.STOPPED, monthStart.plus(Duration.ofHours(10)));

        LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        snapshot(first, "10.00", "12.00");
        snapshot(first.plusDays(1), "5.00", null);
        snapshot(first.minusDays(1), "500.00", null); // last month

        QuotaUsageService.Usage usage = usageService.monthToDate(team.getTeamId(), monthStart.plus(Duration.ofDays(1)));
        assertThat(usage.vmHours()).isEqualByComparingTo("10.00");
        assertThat(usage.cost()).isEqualByComparingTo("17.00");
    }

    private void snapshot(LocalDate day, String estimated, String actual) {
        if (day.getMonthValue() != LocalDate.now(ZoneOffset.UTC).getMonthValue() && day.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            return;
        }
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(day));
        s.setEstimatedCost(new BigDecimal(estimated));
        s.setActualCost(actual == null ? null : new BigDecimal(actual));
        snapshots.saveAndFlush(s);
    }

    @Test
    void aBlockQuotaRefusesTheStart() throws Exception {
        quota("[{\"metric\":\"VM_HOURS\",\"limit\":1,\"enforcement\":\"BLOCK\"}]");
        expectError(start(), 409)
                .andExpect(jsonPath("$.message").value("Team quota exceeded: 0 of 1 VM-hours used; this start adds 8 VM-hours"));

        ReflectionTestUtils.setField(quotaTarget(), "enabled", false);
        start().andExpect(status().isAccepted()); // quotas off: never blocked
    }

    @Test
    void requireApprovalHoldsTheStart() throws Exception {
        quota("[{\"metric\":\"VM_HOURS\",\"limit\":1,\"enforcement\":\"REQUIRE_APPROVAL\"}]");
        start().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.reasons[0]").value(containsString("Team quota exceeded")));
    }

    @Test
    void warnLetsItRunWithAWarning() throws Exception {
        quota("[{\"metric\":\"VM_HOURS\",\"limit\":1,\"enforcement\":\"WARN\"}]");
        start().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(header().string("X-Quota-Warning", containsString("Team+quota+exceeded")));
    }

    @Test
    void quotaApiAndMonthlyWarnings() throws Exception {
        quota("[{\"metric\":\"COST\",\"limit\":100,\"enforcement\":\"WARN\",\"warnPercent\":80}]");
        mockMvc.perform(get("/api/v1/teams/" + team.getTeamId() + "/quota-usage").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].metric").value("COST"))
                .andExpect(jsonPath("$[0].limit").value(100.0));
        mockMvc.perform(get("/api/v1/teams/" + team.getTeamId() + "/quota-usage").with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/teams/" + team.getTeamId() + "/quotas").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
        expectError(mockMvc.perform(put("/api/v1/teams/" + team.getTeamId() + "/quotas").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("[{\"metric\":\"COST\",\"limit\":0,\"enforcement\":\"WARN\"}]")), 400);

        snapshot(LocalDate.now(ZoneOffset.UTC), "85.00", null);
        assertThat(quotaService.sendWarnings()).isEqualTo(1);
        assertThat(quotaService.sendWarnings()).isZero(); // once a month
        assertThat(notifications.findAll()).filteredOn(n -> n.getType() == NotificationType.QUOTA_THRESHOLD)
                .singleElement().satisfies(n -> {
                    assertThat(n.getUserId()).isEqualTo(operator.getUserId());
                    assertThat(n.getTitle()).isEqualTo("Team quota at 80%: Quota team");
                });
    }
}
