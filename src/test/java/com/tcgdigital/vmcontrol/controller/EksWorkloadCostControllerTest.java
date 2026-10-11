package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EksWorkloadCostDailyRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Namespace and workload cost of an EKS environment (E26-T05): totals by namespace with the
 * unused capacity, the admin and environment endpoints' role rules, and the 1-90 day bounds.
 */
class EksWorkloadCostControllerTest extends SecuredWebTestBase {

    @Autowired private EksWorkloadCostDailyRepository costs;

    private Environment env;
    private VmGroup group;

    @BeforeEach
    void seed() {
        env = newEnvironment("EKS workloads");
        env.setServiceType("EKS");
        environmentRepository.saveAndFlush(env);
        group = newGroup(env, "workers");
        Date yesterday = Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        Date longAgo = Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(20));
        Timestamp now = Timestamp.from(Instant.now());
        costs.save(new EksWorkloadCostDaily(env.getEnvironmentId(), yesterday, "payments", "api", new BigDecimal("30"), new BigDecimal("3"), now));
        costs.save(new EksWorkloadCostDaily(env.getEnvironmentId(), yesterday, "payments", "worker", new BigDecimal("10"), BigDecimal.ZERO, now));
        costs.save(new EksWorkloadCostDaily(env.getEnvironmentId(), yesterday, "search", "indexer", new BigDecimal("10"), new BigDecimal("2"), now));
        costs.save(new EksWorkloadCostDaily(env.getEnvironmentId(), longAgo, "search", "indexer", new BigDecimal("99"), BigDecimal.ZERO, now));
        costs.flush();
    }

    private String adminUrl(int days) {
        return "/api/v1/cost-management/eks-workload-cost?environmentId=" + env.getEnvironmentId() + "&days=" + days;
    }

    private String envUrl(int days) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/eks-workload-cost?days=" + days;
    }

    @Test
    void anAdminSeesNamespacesByCostWithTheUnusedCapacityForSevenDays() throws Exception {
        mockMvc.perform(get(adminUrl(7)).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespaces.length()").value(2))
                .andExpect(jsonPath("$.namespaces[0].namespace").value("payments"))
                .andExpect(jsonPath("$.namespaces[0].splitCost").value(40.0))
                .andExpect(jsonPath("$.namespaces[0].workloads.length()").value(2))
                .andExpect(jsonPath("$.namespaces[0].workloads[0].workload").value("api"))
                .andExpect(jsonPath("$.namespaces[1].namespace").value("search"))
                .andExpect(jsonPath("$.namespaces[1].splitCost").value(10.0))
                .andExpect(jsonPath("$.unusedCost").value(5.0))
                .andExpect(jsonPath("$.totalSplitCost").value(50.0))
                .andExpect(jsonPath("$.unusedShare").value(9.1));

        // 30 days reach the older row too.
        mockMvc.perform(get(adminUrl(30)).with(asAdmin()))
                .andExpect(jsonPath("$.namespaces[0].namespace").value("search"))
                .andExpect(jsonPath("$.namespaces[0].splitCost").value(109.0));
    }

    @Test
    void daysOutsideOneToNinetyAreRejected() throws Exception {
        expectError(mockMvc.perform(get(adminUrl(0)).with(asAdmin())), 400);
        expectError(mockMvc.perform(get(adminUrl(91)).with(asAdmin())), 400);
        mockMvc.perform(get(adminUrl(90)).with(asAdmin())).andExpect(status().isOk());
        expectError(mockMvc.perform(get(envUrl(91)).with(asAdmin())), 400);
    }

    @Test
    void theAdminEndpointIsForAdminsOnly() throws Exception {
        mockMvc.perform(get(adminUrl(7)).with(asEnvAdmin())).andExpect(status().isForbidden());
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.ADMIN);
        mockMvc.perform(get(adminUrl(7)).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cost-management/eks-workload-cost?environmentId=nope&days=7").with(asAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theEnvironmentEndpointNeedsAViewOfTheWholeEnvironment() throws Exception {
        mockMvc.perform(get(envUrl(7)).with(asViewer())).andExpect(status().isForbidden());

        grantGroup(viewer, group.getGroupId(), AccessLevel.VIEWER);
        mockMvc.perform(get(envUrl(7)).with(asViewer())).andExpect(status().isForbidden());

        grantEnv(operator, env.getEnvironmentId(), AccessLevel.VIEWER);
        mockMvc.perform(get(envUrl(7)).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespaces[0].splitCost").value(40.0));

        mockMvc.perform(get(envUrl(7)).with(asEnvAdmin())).andExpect(status().isOk());
    }
}
