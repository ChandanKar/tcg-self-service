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

    // ---- My Activity (E11-T06) ----

    private void rowWithDetails(String userId, String details, Instant at) {
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, target_id, "
                        + "target_name, action_status, details, created_at) VALUES (?, ?, ?, 'ACCESS_GRANTED', 'test', ?, ?, 'succeeded', ?, ?)",
                UUID.randomUUID().toString(), userId, env1.getEnvironmentId(), UUID.randomUUID().toString(),
                marker, details, Timestamp.from(at));
    }

    @Test
    void myActivityListsOnlyRowsWhereIAmTheActor() throws Exception {
        Instant at = Instant.now().minus(Duration.ofHours(1));
        rowWithDetails(operator.getUserId(), "I did this", at);
        rowWithDetails(admin.getUserId(), "Access granted to " + operator.getUserId(), at); // operator is only the subject

        mockMvc.perform(get("/api/v1/audit/logs/my").with(asUser()).param("from", Instant.now().minus(Duration.ofDays(1)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.targetName == '" + marker + "')].userId", everyItem(is(operator.getUserId()))))
                .andExpect(jsonPath("$.content[?(@.targetName == '" + marker + "')]").value(org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void myActivityHonoursFromToAndCapsThePageSize() throws Exception {
        Instant now = Instant.now();
        rowWithDetails(operator.getUserId(), "recent", now.minus(Duration.ofHours(1)));
        rowWithDetails(operator.getUserId(), "old", now.minus(Duration.ofDays(3)));

        mockMvc.perform(get("/api/v1/audit/logs/my").with(asUser())
                        .param("from", now.minus(Duration.ofDays(1)).toString()).param("to", now.toString()).param("size", "10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(500))
                .andExpect(jsonPath("$.content[?(@.targetName == '" + marker + "')].details").value(org.hamcrest.Matchers.contains("recent")));
    }

    // ---- My activity action groups (U09-T04) ----

    private void myRow(String action, String details) {
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, target_id, "
                        + "target_name, action_status, details, created_at) VALUES (?, ?, ?, ?, 'test', ?, ?, 'succeeded', ?, ?)",
                UUID.randomUUID().toString(), operator.getUserId(), env1.getEnvironmentId(), action,
                UUID.randomUUID().toString(), marker, details, Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private org.springframework.test.web.servlet.ResultActions myActivity(String... params) throws Exception {
        var request = get("/api/v1/audit/logs/my").with(asUser()).param("from", Instant.now().minus(Duration.ofDays(1)).toString());
        for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void myActivityFiltersByActionGroupOnTheServer() throws Exception {
        myRow("OPERATION_COMPLETED", "start");
        myRow("VM_START_FAILED", "vm");
        myRow("LEASE_EXTENDED", "lease");
        myRow("LOCK_ACQUIRED", "lock");
        myRow("LOCK_RELEASED", "unlock");
        myRow("ACCESS_REQUESTED", "access");
        myRow("ENVIRONMENT_UPDATED", "other");
        String mine = "$.content[?(@.targetName == '" + marker + "')].details";

        myActivity("category", "OPERATIONS").andExpect(jsonPath(mine)
                .value(org.hamcrest.Matchers.containsInAnyOrder("start", "vm", "lease")));
        myActivity("category", "LOCKS").andExpect(jsonPath(mine)
                .value(org.hamcrest.Matchers.containsInAnyOrder("lock", "unlock")));
        myActivity("category", "ACCESS").andExpect(jsonPath(mine)
                .value(org.hamcrest.Matchers.contains("access")));
        myActivity().andExpect(jsonPath(mine).value(org.hamcrest.Matchers.hasSize(7)));
    }

    @Test
    void myActivityPagesCountOnlyTheGroup() throws Exception {
        for (int i = 0; i < 3; i++) myRow("LOCK_ACQUIRED", "lock " + i);
        myRow("ACCESS_REQUESTED", "access");
        myActivity("category", "LOCKS", "environmentId", env1.getEnvironmentId(), "size", "2")
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void anActionWinsOverTheGroup() throws Exception {
        myRow("LOCK_ACQUIRED", "lock");
        myRow("ACCESS_REQUESTED", "access");
        myActivity("category", "LOCKS", "action", "ACCESS_REQUESTED")
                .andExpect(jsonPath("$.content[?(@.targetName == '" + marker + "')].details")
                        .value(org.hamcrest.Matchers.contains("access")));
    }

    @Test
    void anUnknownGroupIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/audit/logs/my").with(asUser()).param("category", "EVERYTHING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyGroupHasActionsAndNoActionIsInTwoGroups() {
        java.util.Set<com.tcgdigital.vmcontrol.model.AuditAction> seen = java.util.EnumSet.noneOf(com.tcgdigital.vmcontrol.model.AuditAction.class);
        for (var category : com.tcgdigital.vmcontrol.model.AuditActionCategory.values()) {
            org.assertj.core.api.Assertions.assertThat(category.actionNames()).isNotEmpty();
            for (var action : category.actions()) {
                org.assertj.core.api.Assertions.assertThat(seen.add(action)).as(action + " in two groups").isTrue();
            }
        }
    }

    @Test
    void theActorBackfillUsesOnlyTheExplicitByUserPattern() throws Exception {
        String byActor = UUID.randomUUID().toString();
        String bySubject = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, action_type, target_type, target_id, action_status, details) "
                + "VALUES (?, NULL, 'ACCESS_GRANTED', 'test', 't', 'succeeded', ?)", byActor, "Access granted by user: " + operator.getUserId());
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, action_type, target_type, target_id, action_status, details) "
                + "VALUES (?, NULL, 'ACCESS_GRANTED', 'test', 't', 'succeeded', ?)", bySubject, "Access granted to " + operator.getUserId());

        String sql = new String(new org.springframework.core.io.ClassPathResource("db/migration/V40__backfill_audit_actor.sql")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        jdbcTemplate.update(sql.replaceAll("(?m)^--.*$", "").trim().replaceAll(";$", ""));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM audit_log WHERE audit_id = ?",
                String.class, byActor)).isEqualTo(operator.getUserId());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM audit_log WHERE audit_id = ?",
                String.class, bySubject)).isNull();
    }

    // ---- U12-T01: action categories

    private void rowOf(String action, Environment env, String status) {
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, target_id, "
                        + "target_name, action_status, created_at) VALUES (?, ?, ?, ?, 'test', ?, ?, ?, ?)",
                UUID.randomUUID().toString(), operator.getUserId(), env.getEnvironmentId(), action, UUID.randomUUID().toString(),
                marker, status, Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private void seedCategories() {
        rowOf("AUTOMATION_RULE_TRIGGERED", env1, "succeeded");
        rowOf("AUTOMATION_RULE_SKIPPED", env1, "succeeded");
        rowOf("AUTOMATION_RULE_FAILED", env2, "failed");
        rowOf("HOLIDAY_CALENDAR_CHANGED", env1, "succeeded");
        rowOf("ACCESS_GRANTED", env1, "succeeded");
        rowOf("ACCESS_DENIED", env1, "failed");
        rowOf("ACCESS_REVOKED", env2, "failed");
        rowOf("LOCK_ACQUIRED", env1, "succeeded");
    }

    @Test
    void aCategoryReturnsOnlyItsActions() throws Exception {
        seedCategories();
        mockMvc.perform(get(url("logs", "category=AUTOMATION")).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[*].action", everyItem(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.startsWith("AUTOMATION_RULE_"), is("HOLIDAY_CALENDAR_CHANGED")))));
    }

    @Test
    void anActionAndACategoryCombine() throws Exception {
        seedCategories();
        mockMvc.perform(get(url("logs", "category=AUTOMATION&action=AUTOMATION_RULE_SKIPPED")).with(asAdmin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].action").value("AUTOMATION_RULE_SKIPPED"));
        // An action outside the category matches nothing.
        mockMvc.perform(get(url("logs", "category=AUTOMATION&action=LOCK_ACQUIRED")).with(asAdmin()))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get(url("logs/stats", "category=AUTOMATION&action=LOCK_ACQUIRED")).with(asAdmin()))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theStatsUseTheCategoryToo() throws Exception {
        seedCategories();
        mockMvc.perform(get(url("logs/stats", "category=ACCESS&success=false")).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(url("logs", "category=ACCESS&success=false")).with(asAdmin()))
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get(url("logs/stats", "category=ACCESS")).with(asAdmin()))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.failures").value(2));
    }

    @Test
    void aCategoryNeverWidensAnAssignedScope() throws Exception {
        seedCategories();
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        grantEnv(envAdmin, env1.getEnvironmentId(), AccessLevel.ADMIN);

        mockMvc.perform(get(url("logs", "category=AUTOMATION")).with(asEnvAdmin()))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[*].environmentId", everyItem(is(env1.getEnvironmentId()))));
    }

    @Test
    void anUnknownCategoryIs400AndTheCategoriesAreListed() throws Exception {
        expectError(mockMvc.perform(get(url("logs", "category=NOPE")).with(asAdmin())), 400);
        mockMvc.perform(get("/api/v1/audit/categories").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category == 'OPERATIONS')].label").value("Start and stop"))
                .andExpect(jsonPath("$[?(@.category == 'AUTOMATION')].actions[*]", org.hamcrest.Matchers.hasItem("HOLIDAY_CALENDAR_CHANGED")));
        mockMvc.perform(get("/api/v1/audit/categories").with(asUser())).andExpect(status().isForbidden());
    }
}
