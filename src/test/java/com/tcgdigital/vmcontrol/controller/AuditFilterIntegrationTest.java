package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The global audit log applies every filter together and its stats use the same filters
 * (E11-T04, H19, M38); an ENV_ADMIN scoped to assigned environments sees only those; the page
 * size is capped. Rows are seeded with JDBC and tagged with a per-test marker.
 */
class AuditFilterIntegrationTest extends SecuredWebTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SecurityService securityService;

    private Object originalScope;
    private Environment env1;
    private Environment env2;
    private String marker;

    @BeforeEach
    void setUp() {
        originalScope = ReflectionTestUtils.getField(securityService, "envAdminScope");
        env1 = newEnvironment("Audit One");
        env2 = newEnvironment("Audit Two");
        marker = "t04-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void restoreScope() {
        ReflectionTestUtils.setField(securityService, "envAdminScope", originalScope);
    }

    private void row(String userId, Environment env, String status, Instant at) {
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, target_id, "
                        + "target_name, action_status, created_at) VALUES (?, ?, ?, 'ENVIRONMENT_UPDATED', 'test', ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, env.getEnvironmentId(), UUID.randomUUID().toString(),
                marker, status, Timestamp.from(at));
    }

    private String url(String path, String query) {
        return "/api/v1/audit/" + path + "?search=" + marker + "&from=" + Instant.now().minus(Duration.ofDays(30))
                + (query.isEmpty() ? "" : "&" + query);
    }

    @Test
    void userAndTimeFiltersApplyTogether() throws Exception {
        Instant now = Instant.now();
        row(operator.getUserId(), env1, "succeeded", now.minus(Duration.ofHours(2)));
        row(operator.getUserId(), env1, "succeeded", now.minus(Duration.ofDays(2)));
        row(viewer.getUserId(), env1, "succeeded", now.minus(Duration.ofHours(1)));

        mockMvc.perform(get("/api/v1/audit/logs").with(asAdmin())
                        .param("search", marker).param("userId", operator.getUserId())
                        .param("from", now.minus(Duration.ofHours(24)).toString()).param("to", now.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(operator.getUserId()));
    }

    @Test
    void theFailureFilterReturnsOnlyFailures() throws Exception {
        Instant now = Instant.now();
        row(operator.getUserId(), env1, "succeeded", now.minus(Duration.ofHours(1)));
        row(operator.getUserId(), env1, "failed", now.minus(Duration.ofHours(1)));

        mockMvc.perform(get(url("logs", "success=false")).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[*].success", everyItem(is(false))));
    }

    @Test
    void statsCountEveryMatchingRowNotOnePage() throws Exception {
        Instant at = Instant.now().minus(Duration.ofHours(3));
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            batch.add(new Object[]{UUID.randomUUID().toString(), operator.getUserId(), env1.getEnvironmentId(),
                    UUID.randomUUID().toString(), marker, i < 7 ? "failed" : "succeeded", Timestamp.from(at)});
        }
        jdbcTemplate.batchUpdate("INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, "
                + "target_id, target_name, action_status, created_at) VALUES (?, ?, ?, 'ENVIRONMENT_UPDATED', 'test', ?, ?, ?, ?)", batch);

        mockMvc.perform(get(url("logs/stats", "")).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(120))
                .andExpect(jsonPath("$.failures").value(7))
                .andExpect(jsonPath("$.successRate").value(94))
                .andExpect(jsonPath("$.topUser.id").value(operator.getUserId()))
                .andExpect(jsonPath("$.topEnvironment.id").value(env1.getEnvironmentId()));
        mockMvc.perform(get(url("logs", "size=10")).with(asAdmin()))
                .andExpect(jsonPath("$.page.totalElements").value(120));
    }

    @Test
    void anAssignedEnvAdminSeesOnlyTheirEnvironments() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        grantEnv(envAdmin, env1.getEnvironmentId(), AccessLevel.ADMIN);
        Instant at = Instant.now().minus(Duration.ofHours(1));
        row(operator.getUserId(), env1, "succeeded", at);
        row(operator.getUserId(), env2, "succeeded", at);

        mockMvc.perform(get(url("logs", "")).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].environmentId").value(env1.getEnvironmentId()));
        expectError(mockMvc.perform(get(url("logs", "environmentId=" + env2.getEnvironmentId())).with(asEnvAdmin())), 404);
        mockMvc.perform(get(url("logs/stats", "")).with(asEnvAdmin()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void anAssignedEnvAdminWithNoEnvironmentsGetsAnEmptyPage() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        row(operator.getUserId(), env1, "succeeded", Instant.now().minus(Duration.ofHours(1)));

        mockMvc.perform(get(url("logs", "")).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void theOldAllFiltersAtOnceDefaultStillWorksAndPageSizeIsCapped() throws Exception {
        row(operator.getUserId(), env1, "succeeded", Instant.now().minus(Duration.ofHours(1)));

        mockMvc.perform(get("/api/v1/audit/logs").with(asAdmin()).param("search", marker).param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(500))
                .andExpect(jsonPath("$.content.length()").value(1));
    }
}
