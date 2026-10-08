package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Non-global admins cannot change their own access: no self-grant, no extending or clearing the
 * expiry of their own grant, no approving their own request (E03-T06, H16).
 */
class AccessGrantControllerSecurityTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentAccessRepository grants;

    @Autowired
    private EnvironmentAccessService accessService;

    private Environment env;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Granted");
    }

    /** A grant expiring in 3 days. */
    private EnvironmentAccess expiringGrant(User user, AccessLevel level) {
        EnvironmentAccess grant = grantEnv(user, env.getEnvironmentId(), level);
        grant.setExpiresAt(Timestamp.from(Instant.now().plus(3, ChronoUnit.DAYS)));
        return grants.save(grant);
    }

    private EnvironmentAccessRequest requestBy(User requester) {
        return requestBy(requester, env);
    }

    private EnvironmentAccessRequest requestBy(User requester, Environment target) {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for a release");
        return accessService.createAccessRequest(target.getEnvironmentId(), requester.getUserId(), dto);
    }

    @Test
    void environmentAdminCannotClearTheExpiryOfTheirOwnGrant() throws Exception {
        EnvironmentAccess own = expiringGrant(operator, AccessLevel.ADMIN);
        Timestamp before = grants.findById(own.getAccessId()).orElseThrow().getExpiresAt(); // as stored

        expectError(mockMvc.perform(patch("/api/v1/access-grants/" + own.getAccessId()).with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clearExpiry\":true}")), 403);

        assertThat(grants.findById(own.getAccessId()).orElseThrow().getExpiresAt()).isEqualTo(before);
    }

    @Test
    void environmentAdminCanStillChangeSomeoneElsesGrant() throws Exception {
        expiringGrant(operator, AccessLevel.ADMIN);
        EnvironmentAccess viewersGrant = expiringGrant(viewer, AccessLevel.VIEWER);

        mockMvc.perform(patch("/api/v1/access-grants/" + viewersGrant.getAccessId()).with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessLevel\":\"USER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void environmentAdminCannotGrantThemselves() throws Exception {
        expiringGrant(operator, AccessLevel.ADMIN);
        String body = "{\"userEmail\":\"" + operator.getEmail() + "\",\"environmentId\":\"" + env.getEnvironmentId()
                + "\",\"accessLevel\":\"ADMIN\",\"scopeType\":\"ENVIRONMENT\"}";

        expectError(mockMvc.perform(post("/api/v1/access-grants").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 403);
    }

    @Test
    void envAdminCannotApproveTheirOwnRequest() throws Exception {
        EnvironmentAccessRequest own = requestBy(envAdmin);

        expectError(mockMvc.perform(post("/api/v1/access-requests/" + own.getRequestId() + "/approve")
                .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}")), 403);
        assertThat(accessService.getAccessRequest(own.getRequestId()).getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void envAdminCanApproveSomeoneElsesRequest() throws Exception {
        EnvironmentAccessRequest other = requestBy(operator);

        mockMvc.perform(post("/api/v1/access-requests/" + other.getRequestId() + "/approve")
                        .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void globalAdminKeepsTheBreakGlassPath() throws Exception {
        EnvironmentAccess own = expiringGrant(admin, AccessLevel.ADMIN);

        mockMvc.perform(patch("/api/v1/access-grants/" + own.getAccessId()).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clearExpiry\":true}"))
                .andExpect(status().isOk());

        EnvironmentAccessRequest adminsRequest = requestBy(admin, newEnvironment("Other"));
        mockMvc.perform(post("/api/v1/access-requests/" + adminsRequest.getRequestId() + "/approve")
                        .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}
