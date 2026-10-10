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
 * group's VMs, not the whole environment's; an admin's summary is unchanged.
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
                .andExpect(jsonPath("$.schedulerHealth.length()").value(17))
                .andExpect(jsonPath("$.schedulerHealth[?(@.owner != null)]").isEmpty());
        mockMvc.perform(get("/api/v1/dashboard/summary").with(asAdmin()))
                .andExpect(jsonPath("$.schedulerHealth[?(@.name == 'Metrics sync')].owner").value(
                        org.hamcrest.Matchers.contains("host-a@pid")));
    }
}
