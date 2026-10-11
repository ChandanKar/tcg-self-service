package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.BudgetService;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read and change budgets (E20-T01): admins all; an environment's owners (effective ADMIN
 * on it) its budget; viewers read it; team budgets are admin only.
 */
class BudgetControllerSecurityTest extends SecuredWebTestBase {

    @Autowired private BudgetService budgetService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment envA;
    private Environment envB;

    @BeforeEach
    void setUpEnvironments() {
        envA = newEnvironment("Budget A");
        envB = newEnvironment("Budget B");
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.ADMIN); // A's owner
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.VIEWER);
        // "Today" is 11 September: 10 complete days of $20.
        ReflectionTestUtils.setField(serviceTarget(), "clock",
                Clock.fixed(LocalDate.of(2026, 9, 11).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        for (int d = 1; d <= 10; d++) {
            jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost) "
                    + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), envA.getEnvironmentId(),
                    java.sql.Date.valueOf(LocalDate.of(2026, 9, d)), new BigDecimal("20.00"));
        }
    }

    private Object serviceTarget() {
        Object target = AopTestUtils.getTargetObject(budgetService);
        return target;
    }

    @AfterEach
    void realClock() {
        ReflectionTestUtils.setField(serviceTarget(), "clock", Clock.systemUTC());
    }

    private String body(String scopeType, String scopeId, String amount, String extra) {
        return "{\"scopeType\":\"" + scopeType + "\",\"scopeId\":\"" + scopeId + "\",\"monthlyAmount\":" + amount + extra + "}";
    }

    private String createAs(org.springframework.test.web.servlet.request.RequestPostProcessor who, String json) throws Exception {
        String response = mockMvc.perform(post("/api/v1/budgets").with(who).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.budgetId");
    }

    @Test
    void anEnvironmentOwnerCreatesItsBudgetAndSeesTheSpend() throws Exception {
        String id = createAs(asUser(), body("ENVIRONMENT", envA.getEnvironmentId(), "500", ""));

        mockMvc.perform(get("/api/v1/budgets/" + id).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendToDate").value(200.0))
                .andExpect(jsonPath("$.percentUsed").value(40.0))
                .andExpect(jsonPath("$.forecastMonthEnd").value(600.0))
                .andExpect(jsonPath("$.thresholds[0]").value(50))
                .andExpect(jsonPath("$.month").value("2026-09"));
        mockMvc.perform(put("/api/v1/budgets/" + id).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ENVIRONMENT", envA.getEnvironmentId(), "800", ",\"thresholds\":[100,80]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds[0]").value(80));
        // Not the owner of B.
        mockMvc.perform(post("/api/v1/budgets").with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ENVIRONMENT", envB.getEnvironmentId(), "500", ""))).andExpect(status().isForbidden());
    }

    @Test
    void aViewerReadsButCannotCreate() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/budgets").with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ENVIRONMENT", envA.getEnvironmentId(), "500", ""))), 403);
        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/budget").with(asViewer()))
                .andExpect(status().isNoContent());

        String id = createAs(asAdmin(), body("ENVIRONMENT", envA.getEnvironmentId(), "500", ""));
        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/budget").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetId").value(id));
        mockMvc.perform(get("/api/v1/environments/" + envB.getEnvironmentId() + "/budget").with(asViewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/budgets/" + id).with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/budgets").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void teamBudgetsAreForAdminsOnly() throws Exception {
        String team = body("TEAM", "Payments", "2000", "");
        expectError(mockMvc.perform(post("/api/v1/budgets").with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(team)), 403);
        String id = createAs(asAdmin(), team);
        mockMvc.perform(get("/api/v1/budgets/" + id).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/budgets").with(asViewer()))
                .andExpect(jsonPath("$.length()").value(0));
        // Actions are for environment budgets only.
        expectError(mockMvc.perform(put("/api/v1/budgets/" + id).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("TEAM", "Payments", "2000", ",\"actionType\":\"FREEZE_STARTS\""))), 400);
        mockMvc.perform(get("/api/v1/budgets/teams").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/budgets/teams").with(asUser())).andExpect(status().isForbidden());
    }

    @Test
    void badBodiesAre400AndOneBudgetPerScope() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/budgets").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ENVIRONMENT", envA.getEnvironmentId(), "500", ",\"thresholds\":[90,50,50]"))), 400)
                .andExpect(jsonPath("$.message").value("Thresholds must be 1 to 4 different whole percentages between 1 and 200"));
        expectError(mockMvc.perform(post("/api/v1/budgets").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ENVIRONMENT", envA.getEnvironmentId(), "0", ""))), 400);
        createAs(asAdmin(), body("ENVIRONMENT", envA.getEnvironmentId(), "500", ""));
        expectError(mockMvc.perform(post("/api/v1/budgets").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("ENVIRONMENT", envA.getEnvironmentId(), "600", ""))), 400)
                .andExpect(jsonPath("$.message").value("This environment already has a budget"));
        mockMvc.perform(get("/api/v1/budgets")).andExpect(status().is(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(403))));
    }
}
