package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The self-service token API (E34-T01): own tokens only, the token shown once, role scopes, the switch. */
class ApiTokenControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/me/api-tokens";

    @Autowired private ApiTokenService tokenService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void tokens(boolean on) {
        Object target = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    @BeforeEach
    void on() {
        tokens(true);
    }

    @AfterEach
    void off() {
        tokens(false);
    }

    private String create(String scopes) throws Exception {
        return mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci\",\"scopes\":" + scopes + ",\"expiresInDays\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theTokenIsShownOnceAndListedByItsPrefix() throws Exception {
        String json = create("[\"environments:read\"]");
        String token = JsonPath.read(json, "$.token");
        String prefix = JsonPath.read(json, "$.prefix");
        assertThat(token).startsWith("vmc_" + prefix + "_");

        String listed = mockMvc.perform(get(URL).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].prefix").value(prefix))
                .andExpect(jsonPath("$[0].scopes[0]").value("environments:read"))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(token.substring(13));
        String stored = jdbcTemplate.queryForObject("SELECT CONCAT(token_prefix, token_hash, scopes) FROM api_token", String.class);
        assertThat(stored).doesNotContain(token.substring(13));
    }

    @Test
    void aUserCannotAskForTheAdminScope() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"scopes\":[\"admin\"],\"expiresInDays\":30}")), 400)
                .andExpect(jsonPath("$.message", containsString("admin")));
        mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"scopes\":[\"admin\"],\"expiresInDays\":30}")).andExpect(status().isCreated());
    }

    @Test
    void onlyOwnTokensAreListedAndRevoked() throws Exception {
        String id = JsonPath.read(create("[\"cost:read\"]"), "$.id");

        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(jsonPath("$.length()").value(0));
        expectError(mockMvc.perform(delete(URL + "/" + id).with(asEnvAdmin())), 404);
        mockMvc.perform(delete(URL + "/" + id).with(asUser())).andExpect(status().isOk());
        mockMvc.perform(get(URL).with(asUser())).andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
    }

    @Test
    void switchedOffEverythingIsNotFound() throws Exception {
        tokens(false);

        expectError(mockMvc.perform(get(URL).with(asUser())), 404);
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"scopes\":[\"cost:read\"],\"expiresInDays\":30}")), 404);
    }

    // --- Admin oversight (E34-T05) ---

    @Test
    void anAdminSeesEveryActiveTokenWithItsOwnerAndRevokesAny() throws Exception {
        String json = create("[\"environments:read\"]");
        String id = JsonPath.read(json, "$.id");
        String token = JsonPath.read(json, "$.token");

        mockMvc.perform(get("/api/v1/api-tokens").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].token.id").value(id))
                .andExpect(jsonPath("$.content[0].ownerUserId").value(operator.getUserId()))
                .andExpect(jsonPath("$.content[0].token.token").doesNotExist());
        mockMvc.perform(get("/api/v1/api-tokens").with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/api-tokens/" + id).with(asUser())).andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/api-tokens/" + id).with(asAdmin())).andExpect(status().isOk());
        assertThat(tokenService.verify(token)).isEmpty();
        mockMvc.perform(get("/api/v1/api-tokens").with(asAdmin())).andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/api-tokens?includeInactive=true").with(asAdmin())).andExpect(jsonPath("$.content.length()").value(1));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'API_TOKEN_REVOKED' AND details LIKE '%revoked by an admin%'",
                Integer.class)).isEqualTo(1));
    }
}
