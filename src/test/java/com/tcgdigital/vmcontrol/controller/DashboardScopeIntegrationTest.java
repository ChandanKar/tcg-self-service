package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard counts only VMs the user may see (E11-T11, H20): a group-grant user sees their
 * group's VMs, not the whole environment's; an admin's summary is unchanged. Home (U06-T01): each
 * card carries the viewer's access, the lock holder, the lease end and group states, and needsYou
 * lists leases ending soon and grants about to end.
 */
class DashboardScopeIntegrationTest extends SecuredWebTestBase {

    @Test
    void aGroupGrantUserSeesOnlyTheirGroupsVms() throws Exception {
        Environment env = newEnvironment("Dash scope");
        VmGroup web = newGroup(env, "web");
        VmGroup db = newGroup(env, "db");
        newVm(web, "web-1", VmStatus.RUNNING);
        newVm(web, "web-2", VmStatus.STOPPED);
        newVm(db, "db-1", VmStatus.RUNNING);
        newVm(db, "db-2", VmStatus.RUNNING);
        newVm(db, "db-3", VmStatus.STOPPED);
        grantGroup(operator, web.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalVms").value(2))
                .andExpect(jsonPath("$.summary.runningVms").value(1));

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalVms", greaterThanOrEqualTo(5)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.StateSyncService stateSyncService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository stateHistory;

    @Test
    void theSummaryCountsTheVmsChangedOutsideThePlatformIn24HoursAsHomesAttentionDoes() throws Exception {
        // U23-T09 (CloudOps finding H): the summary said 0 from the drift flag (cleared at the next sync)
        // while the attention said 102. Now both count the VMs with a change made outside in 24 h.
        jdbcTemplate.update("DELETE FROM vm_state_history");
        Environment env = newEnvironment("Dash drift");
        VmGroup web = newGroup(env, "web");
        VmGroup db = newGroup(env, "db");
        com.tcgdigital.vmcontrol.model.Vm web1 = newVm(web, "web-1", VmStatus.STOPPED);
        com.tcgdigital.vmcontrol.model.Vm web2 = newVm(web, "web-2", VmStatus.STOPPED);
        com.tcgdigital.vmcontrol.model.Vm db1 = newVm(db, "db-1", VmStatus.STOPPED);
        stateSyncService.recordStateChange(web1, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "x");
        stateSyncService.recordStateChange(web1, VmStatus.STOPPED, VmStatus.RUNNING, "state_sync", null, null, "x");
        stateSyncService.recordStateChange(db1, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "x");
        String old = stateSyncService.recordStateChange(web2, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "x").getHistoryId();
        jdbcTemplate.update("UPDATE vm_state_history SET created_at = ? WHERE history_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(25 * 3600)), old);
        stateSyncService.recordStateChange(web2, VmStatus.STOPPED, VmStatus.RUNNING, "operation", null, null, "x");
        grantGroup(operator, web.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.driftedVms").value(2))
                .andExpect(jsonPath("$.riskCompliance[?(@.title == 'State drift')].description")
                        .value(org.hamcrest.Matchers.contains("2 VMs changed outside the platform in 24 h.")));
        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(jsonPath("$.summary.driftedVms").value(1)); // web-1 only: db-1 is not theirs, web-2's is old
        org.junit.jupiter.api.Assertions.assertEquals(2, stateHistory.countDriftVmsInRange(
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(24 * 3600)), java.sql.Timestamp.from(java.time.Instant.now())));
    }

    // ---- Scheduler health (E12-T05) ----

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void lockOwnersAreShownToAdminsOnly() throws Exception {
        jdbcTemplate.update("DELETE FROM scheduled_job_lock WHERE lock_name = 'vm_metrics_sync'");
        jdbcTemplate.update("INSERT INTO scheduled_job_lock (lock_name, locked_by, locked_until, acquired_at) VALUES "
                + "('vm_metrics_sync', 'host-a@pid', ?, ?)", java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerHealth.length()").value(35))
                .andExpect(jsonPath("$.schedulerHealth[?(@.owner != null)]").isEmpty());
        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(jsonPath("$.schedulerHealth[?(@.name == 'Metrics sync')].owner").value(
                        org.hamcrest.Matchers.contains("host-a@pid")));
    }

    // ---- Home (U06-T01) ----

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository leases;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.LockService lockService;

    private String card(Environment env, String field) {
        return "$.environments[?(@.environmentId == '" + env.getEnvironmentId() + "')]." + field;
    }

    @Test
    void aLeaseEndingWithinThreeHoursIsSomethingTheOperatorShouldAct() throws Exception {
        Environment env = newEnvironment("Lease soon");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        java.time.Instant ends = java.time.Instant.now().plus(java.time.Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        com.tcgdigital.vmcontrol.model.EnvironmentLease lease = new com.tcgdigital.vmcontrol.model.EnvironmentLease();
        lease.setLeaseId(java.util.UUID.randomUUID().toString());
        lease.setEnvironment(env);
        lease.setStatus(com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE);
        lease.setSource(com.tcgdigital.vmcontrol.model.LeaseSource.START);
        lease.setStartedByUserId(operator.getUserId());
        lease.setStartedAt(java.sql.Timestamp.from(java.time.Instant.now()));
        lease.setEndsAt(java.sql.Timestamp.from(ends));
        leases.saveAndFlush(lease);

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsYou[?(@.kind == 'LEASE_ENDING' && @.environmentId == '" + env.getEnvironmentId() + "')].referenceId")
                        .value(org.hamcrest.Matchers.contains(lease.getLeaseId())))
                .andExpect(jsonPath("$.needsYou[?(@.kind == 'LEASE_ENDING')].at")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith(ends.toString().substring(0, 16)))))
                .andExpect(jsonPath(card(env, "leaseEndsAt")).value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())))
                .andExpect(jsonPath(card(env, "myAccess")).value(org.hamcrest.Matchers.contains("USER")))
                .andExpect(jsonPath(card(env, "groupStates[0].running")).value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void aViewerGrantEndingInThreeDaysIsListedAndTheCardSaysViewer() throws Exception {
        Environment env = newEnvironment("Grant ending");
        var grant = grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        grant.setExpiresAt(java.sql.Timestamp.from(java.time.Instant.now().plus(java.time.Duration.ofDays(3))));
        environmentAccessRepository.saveAndFlush(grant);

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsYou[?(@.kind == 'ACCESS_ENDING')].referenceId")
                        .value(org.hamcrest.Matchers.contains(grant.getAccessId())))
                .andExpect(jsonPath(card(env, "myAccess")).value(org.hamcrest.Matchers.contains("VIEWER")))
                .andExpect(jsonPath("$.summary.monthToDateCost").isNumber());
    }

    @Test
    void anEnvironmentLockedBySomeoneElseNamesTheHolder() throws Exception {
        Environment env = newEnvironment("Locked home");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), envAdmin.getUserId(), "release testing", null);

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(card(env, "lockedByName")).value(org.hamcrest.Matchers.contains(envAdmin.getDisplayName())))
                .andExpect(jsonPath(card(env, "lockedByMe")).value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath(card(env, "locked")).value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void theAdminSummaryKeepsItsFieldsAndHasNothingForHome() throws Exception {
        Environment env = newEnvironment("Admin home");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona").value("ADMIN"))
                .andExpect(jsonPath("$.schedulerHealth").isArray())
                .andExpect(jsonPath("$.riskCompliance").isArray())
                .andExpect(jsonPath("$.summary.pendingAccessRequests").isNumber())
                .andExpect(jsonPath("$.needsYou").isEmpty())
                // U14-T01: Home's spend line and fleet strips
                .andExpect(jsonPath("$.summary.monthToDateCost").isNumber())
                .andExpect(jsonPath(card(env, "groupStates[0].running")).value(org.hamcrest.Matchers.contains(1)));
    }

    /**
     * U21-T18: an administrator's Home said "$0 this month" on staging while Cost management showed
     * $2,155. getSummary built the admin summary without the user, so every environment's cost row
     * came back empty. The month to date now includes this month's estimates, as for other users.
     */
    @Test
    void theAdminMonthToDateAddsUpThisMonthsCost() throws Exception {
        Environment env = newEnvironment("Admin spend");
        newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        java.time.LocalDate firstOfMonth = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1);
        jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost, created_at) "
                + "VALUES (UUID(), ?, ?, 123.45, NOW())", env.getEnvironmentId(), java.sql.Date.valueOf(firstOfMonth));

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona").value("ADMIN"))
                .andExpect(jsonPath("$.summary.monthToDateCost", greaterThanOrEqualTo(123.45)));
    }

    @Test
    void topVmsCarryTheirEnvironmentAndGroupButNoInstanceId() throws Exception {
        Environment env = newEnvironment("Top vm");
        newVm(newGroup(env, "batch"), "batch-1", VmStatus.RUNNING);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        jdbcTemplate.update("INSERT INTO vm_idle_summary (idle_summary_id, vm_id, is_idle, idle_duration_minutes, latest_cpu_utilization) "
                + "SELECT UUID(), vm_id, true, 180, 1.5 FROM vm WHERE group_id IN (SELECT group_id FROM vm_group WHERE environment_id = ?)",
                env.getEnvironmentId());

        mockMvc.perform(get("/api/v1/dashboard/summary").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idleVms[?(@.environmentId == '" + env.getEnvironmentId() + "')].groupName")
                        .value(org.hamcrest.Matchers.contains("batch")))
                .andExpect(jsonPath("$.idleVms[*].providerVmId").isEmpty());
    }
}
