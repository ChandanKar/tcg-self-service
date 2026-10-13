package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentCarbonMonthly;
import com.tcgdigital.vmcontrol.repository.EnvironmentCarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.service.carbon.CarbonIngestionService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Carbon endpoints (E37-T03): an environment's carbon for those who can view it, the fleet view for admins, off is 404. */
class CarbonEndpointsSecurityTest extends SecuredWebTestBase {

    @Autowired private CarbonIngestionService ingestionService;
    @Autowired private EnvironmentCarbonMonthlyRepository attributedRepository;

    private Environment a;
    private Environment b;
    private final YearMonth lastMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1);

    private void enabled(boolean on) {
        Object target = AopTestUtils.getTargetObject(ingestionService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    private void row(Environment env, String mbm, String cost) {
        EnvironmentCarbonMonthly r = new EnvironmentCarbonMonthly();
        r.setId(UUID.randomUUID().toString());
        r.setEnvironment(env);
        r.setMonth(lastMonth.atDay(1));
        r.setEmissionsMbmKg(new BigDecimal(mbm));
        r.setEmissionsLbmKg(new BigDecimal(mbm).multiply(BigDecimal.valueOf(2)));
        r.setCostBasis(cost == null ? null : new BigDecimal(cost));
        r.setCoveragePct(new BigDecimal("75.00"));
        attributedRepository.save(r);
    }

    @BeforeEach
    void setUp() {
        enabled(true);
        a = newEnvironment("Carbon A");
        b = newEnvironment("Carbon B");
        grantEnv(viewer, a.getEnvironmentId(), AccessLevel.VIEWER);
        row(a, "30", "300");
        row(b, "15", "100");
        row(null, "15", null);
    }

    @AfterEach
    void off() {
        enabled(false);
    }

    @Test
    void aViewerReadsTheirEnvironmentsCarbonOnly() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + a.getEnvironmentId() + "/carbon").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestMonth").value(lastMonth.toString()))
                .andExpect(jsonPath("$.latestMbmKg").value(30.0))
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.months[11].month").value(lastMonth.toString()))
                .andExpect(jsonPath("$.coverageNote").isNotEmpty());

        expectError(mockMvc.perform(get("/api/v1/environments/" + b.getEnvironmentId() + "/carbon").with(asViewer())), 403);
    }

    @Test
    void theFleetViewAndExportAreForAdmins() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/cost-management/carbon").with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(get("/api/v1/cost-management/carbon/export").with(asViewer())), 403);

        mockMvc.perform(get("/api/v1/cost-management/carbon").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(lastMonth.toString()))
                .andExpect(jsonPath("$.rows[0].environmentId").value(a.getEnvironmentId()))
                .andExpect(jsonPath("$.rows[0].kgPerDollar").value(0.1))
                .andExpect(jsonPath("$.rows[2].unallocated").value(true))
                .andExpect(jsonPath("$.totalMbmKg").value(60.0))
                .andExpect(jsonPath("$.coveragePct").value(75.0))
                .andExpect(jsonPath("$.trend[0].attributedKg").value(45.0))
                .andExpect(jsonPath("$.trend[0].unallocatedKg").value(15.0));

        mockMvc.perform(get("/api/v1/cost-management/carbon/export").param("month", lastMonth.toString()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        expectError(mockMvc.perform(get("/api/v1/cost-management/carbon").param("month", "soon").with(asAdmin())), 400);
    }

    @Test
    void offEverythingIsNotFound() throws Exception {
        enabled(false);

        expectError(mockMvc.perform(get("/api/v1/environments/" + a.getEnvironmentId() + "/carbon").with(asViewer())), 404);
        expectError(mockMvc.perform(get("/api/v1/cost-management/carbon").with(asAdmin())), 404);
        expectError(mockMvc.perform(get("/api/v1/cost-management/carbon/export").with(asAdmin())), 404);
    }
}
