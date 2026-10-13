package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bearer API tokens end to end (E34-T02) on the real Entra security chain: no session, no CSRF,
 * scopes per endpoint (default deny), the owner's rights still apply, revocation, deactivation, the
 * rate limit and the switch.
 */
class ApiTokenAuthenticationIntegrationTest extends SecuredWebTestBase {

    @Autowired private ApiTokenService tokenService;

    private Environment env;

    private Object target() {
        return AopTestUtils.getTargetObject(tokenService);
    }

    @BeforeEach
    void on() {
        // A context left on this thread by another test would be put in a session by MockMvc's test
        // support; a token request must start from nothing.
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(target(), "enabled", true);
        env = newEnvironment("Token env");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(target(), "enabled", false);
        ReflectionTestUtils.setField(target(), "rateLimitPerMinute", 600);
    }

    private String token(String... scopes) {
        return tokenService.create(operator, new CreateApiTokenDTO("ci", List.of(scopes), 30)).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void aReadTokenListsTheOwnersEnvironmentsWithoutASession() throws Exception {
        // MockMvc's csrf() support (used by other tests) swaps in a session-backed CSRF repository, so a
        // session may exist here; what matters is that the token's authentication is never kept in it.
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        MvcResult result = mockMvc.perform(get("/api/v1/environments").session(session)
                        .header("Authorization", bearer(token("environments:read"))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(env.getEnvironmentId());
        assertThat(session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository
                .SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
        mockMvc.perform(get("/api/v1/environments").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutOperationsWriteCannotStartAnEnvironment() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/operations")
                .header("Authorization", bearer(token("environments:read")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"operationType\":\"START\"}")), 403);
    }

    @Test
    void theOwnersAccessStillApplies() throws Exception {
        Environment other = newEnvironment("Not granted");
        expectError(mockMvc.perform(post("/api/v1/environments/" + other.getEnvironmentId() + "/operations")
                .header("Authorization", bearer(token("operations:write")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"operationType\":\"START\"}")), 403);
    }

    @Test
    void aWriteNeedsNoCsrfTokenWithABearer() throws Exception {
        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/lock/acquire")
                        .header("Authorization", bearer(token("operations:write")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ci run\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void unmappedEndpointsAndTokenManagementAreDenied() throws Exception {
        String token = token("environments:read", "cost:read", "audit:read");
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
        mockMvc.perform(get("/api/v1/me/api-tokens").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedTokensDeactivatedOwnersAndTokensOffAre401() throws Exception {
        String revoked = tokenService.create(operator, new CreateApiTokenDTO("old", List.of("environments:read"), 30)).token();
        String id = tokenService.list(operator.getUserId()).get(0).id();
        tokenService.revoke(operator.getUserId(), id, operator.getUserId());
        mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(revoked)))
                .andExpect(status().isUnauthorized());

        String live = token("environments:read");
        operator.setIsActive(false);
        userRepository.saveAndFlush(operator);
        mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(live)))
                .andExpect(status().isUnauthorized());
        operator.setIsActive(true);
        userRepository.saveAndFlush(operator);

        ReflectionTestUtils.setField(target(), "enabled", false);
        mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(live)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/environments").header("Authorization", "Bearer vmc_garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tooManyRequestsAre429WithRetryAfter() throws Exception {
        ReflectionTestUtils.setField(target(), "rateLimitPerMinute", 2);
        String token = token("environments:read");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(token))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void theLastUseIsRecorded() throws Exception {
        String token = token("environments:read");
        mockMvc.perform(get("/api/v1/environments").header("Authorization", bearer(token))).andExpect(status().isOk());

        assertThat(tokenService.list(operator.getUserId()).get(0).lastUsedAt()).isNotNull();
    }
}
