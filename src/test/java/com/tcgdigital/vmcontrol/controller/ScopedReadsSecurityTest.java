package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reads that used to need only a session are now scoped to the environment, group or record
 * (E03-T03: H5, M15).
 */
class ScopedReadsSecurityTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentAccessService accessService;

    private Environment envA;
    private Environment envB;
    private VmGroup groupA1;
    private VmGroup groupA2;
    private Vm vmInB;

    @BeforeEach
    void setUpEnvironments() {
        envA = newEnvironment("Env A");
        envB = newEnvironment("Env B");
        groupA1 = newGroup(envA, "a1");
        groupA2 = newGroup(envA, "a2");
        vmInB = newVm(newGroup(envB, "b1"), "vm-b", VmStatus.RUNNING);
    }

    @Test
    void viewerOfOneEnvironmentCannotReadAnothersAuditLogOrVmHistory() throws Exception {
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.VIEWER);

        expectError(mockMvc.perform(get("/api/v1/audit/logs/environment/" + envB.getEnvironmentId()).with(asViewer())), 403);
        expectError(mockMvc.perform(get("/api/v1/monitoring/vms/" + vmInB.getVmId() + "/history").with(asViewer())), 403);
    }

    @Test
    void environmentAuditLogIsForAdministratorsOfThatEnvironment() throws Exception {
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.ADMIN);
        // An ADMIN grant without an admin role is still not enough (role gate).
        expectError(mockMvc.perform(get("/api/v1/audit/logs/environment/" + envA.getEnvironmentId()).with(asViewer())), 403);

        mockMvc.perform(get("/api/v1/audit/logs/environment/" + envA.getEnvironmentId()).with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void vmHistoryIsReadableWithAccessToTheVmsGroup() throws Exception {
        Vm vmInA = newVm(groupA1, "vm-a", VmStatus.STOPPED);
        grantGroup(operator, groupA1.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/monitoring/vms/" + vmInA.getVmId() + "/history").with(asUser()))
                .andExpect(status().isOk());
        expectError(mockMvc.perform(get("/api/v1/monitoring/vms/does-not-exist/history").with(asUser())), 404);
    }

    @Test
    void fleetWideMonitoringFeedsAreAdminOnly() throws Exception {
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.USER);

        expectError(mockMvc.perform(get("/api/v1/monitoring/state-changes").with(asUser())), 403);
        expectError(mockMvc.perform(get("/api/v1/monitoring/drift-events").with(asUser())), 403);
        expectError(mockMvc.perform(get("/api/v1/monitoring/drift-events/count").with(asUser())
                .param("startDate", "2026-01-01").param("endDate", "2026-01-02")), 403);

        mockMvc.perform(get("/api/v1/monitoring/state-changes").with(asEnvAdmin())).andExpect(status().isOk());
    }

    @Test
    void startOrderOnlyListsVisibleGroups() throws Exception {
        grantGroup(operator, groupA1.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/groups/start-order").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].groupId").value(groupA1.getGroupId()));

        expectError(mockMvc.perform(get("/api/v1/environments/" + envB.getEnvironmentId() + "/groups/start-order")
                .with(asUser())), 403);

        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/groups/start-order").with(asAdmin()))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void accessRequestIsVisibleToItsRequesterAndReviewersOnly() throws Exception {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need to start the web tier");
        EnvironmentAccessRequest request = accessService.createAccessRequest(envA.getEnvironmentId(), operator.getUserId(), dto);
        String url = "/api/v1/access-requests/" + request.getRequestId();

        mockMvc.perform(get(url).with(asUser())).andExpect(status().isOk());
        mockMvc.perform(get(url).with(asEnvAdmin())).andExpect(status().isOk());

        User stranger = newUser("stranger@secured.test", false, false);
        expectError(mockMvc.perform(get(url).with(as(stranger))), 404);
    }
}
