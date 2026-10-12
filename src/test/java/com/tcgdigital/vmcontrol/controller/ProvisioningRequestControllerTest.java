package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment requests (E32-T03): parameter and name checks, approval by the template's approver
 * role (never one's own request), one decision only, and who may see a request.
 */
class ProvisioningRequestControllerTest extends SecuredWebTestBase {

    @Autowired private SettingsService settings;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String URL = "/api/v1/provisioning-requests";
    private String templateId;

    @BeforeEach
    void seed() throws Exception {
        settings.set("provisioning.enabled", "true", admin.getUserId());
        String json = mockMvc.perform(post("/api/v1/templates").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dev stack\",\"engine\":\"SERVICE_CATALOG\","
                                + "\"engineRef\":{\"productId\":\"prod-abc\",\"provisioningArtifactId\":\"pa-1\"},"
                                + "\"parameterSchema\":[{\"key\":\"size\",\"type\":\"enum\",\"options\":[\"small\",\"large\"],\"default\":\"small\"},"
                                + "{\"key\":\"owner\",\"type\":\"string\",\"required\":true,\"pattern\":\"[a-z.]+\"},"
                                + "{\"key\":\"count\",\"type\":\"number\"}],"
                                + "\"defaultTtlHours\":48,\"maxTtlHours\":168,\"approverRole\":\"ENV_ADMIN\",\"allowedRoles\":[\"USER\",\"ENV_ADMIN\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        templateId = JsonPath.read(json, "$.templateId");
    }

    @AfterEach
    void switchOff() {
        settings.reset("provisioning.enabled", admin.getUserId());
    }

    private String body(String name, String parameters, Integer ttl) {
        return "{\"templateId\":\"" + templateId + "\",\"environmentName\":\"" + name + "\",\"parameters\":" + parameters
                + (ttl == null ? "" : ",\"ttlHours\":" + ttl) + ",\"justification\":\"Feature testing\"}";
    }

    private String request(String name) throws Exception {
        String json = mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, "{\"owner\":\"team.a\",\"count\":3}", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.ttlHours").value(48))
                .andExpect(jsonPath("$.parameters.size").value("small"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.requestId");
    }

    @Test
    void aValidRequestWaitsForApprovalAndApproversAreTold() throws Exception {
        String id = request("feature-x");

        mockMvc.perform(get(URL + "/pending").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].requestId").value(id))
                .andExpect(jsonPath("$.content[0].canApprove").value(true));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = 'PROVISIONING_REQUESTED' AND user_id = ?",
                Integer.class, envAdmin.getUserId())).isEqualTo(1));
    }

    @Test
    void invalidParametersNamesAndLifetimesAreRefusedNamingTheProblem() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("feature-x", "{}", null))), 400)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'owner' is required")));
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("feature-x", "{\"owner\":\"team.a\",\"size\":\"huge\"}", null))), 400)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'size'")));
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("feature-x", "{\"owner\":\"team.a\",\"colour\":\"red\"}", null))), 400)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'colour' is not part")));
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("feature-x", "{\"owner\":\"team.a\",\"count\":\"many\"}", null))), 400);
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("feature-x", "{\"owner\":\"team.a\"}", 500))), 400);
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("<b>x</b>", "{\"owner\":\"team.a\"}", null))), 400);
    }

    @Test
    void aNameInUseOrRequestedIsRefused() throws Exception {
        String taken = newEnvironment("taken-env").getName(); // stored lower-case, as EnvironmentService stores it
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(taken.toUpperCase(), "{\"owner\":\"team.a\"}", null))), 400);
        request("feature-y");
        expectError(mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body("Feature-Y", "{\"owner\":\"team.a\"}", null))), 400);
    }

    @Test
    void anApproverDecidesOnceAndTheRequesterHearsIt() throws Exception {
        String id = request("feature-z");

        mockMvc.perform(post(URL + "/" + id + "/approve").with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        expectError(mockMvc.perform(post(URL + "/" + id + "/approve").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 409);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = 'PROVISIONING_APPROVED' AND user_id = ?",
                Integer.class, operator.getUserId())).isEqualTo(1));
    }

    @Test
    void nobodyDecidesTheirOwnRequestAndARejectionNeedsAReason() throws Exception {
        String json = mockMvc.perform(post(URL).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("own-env", "{\"owner\":\"ops\"}", null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String own = JsonPath.read(json, "$.requestId");

        expectError(mockMvc.perform(post(URL + "/" + own + "/approve").with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 403);
        expectError(mockMvc.perform(post(URL + "/" + own + "/reject").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"\"}")), 400);
        mockMvc.perform(post(URL + "/" + own + "/reject").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Use the shared dev env\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNotes").value("Use the shared dev env"));
    }

    @Test
    void usersSeeOnlyTheirOwnRequestsAndCanCancelThem() throws Exception {
        String id = request("feature-c");

        expectError(mockMvc.perform(get(URL + "/" + id).with(asViewer())), 404);
        mockMvc.perform(post(URL + "/" + id + "/approve").with(asUser()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/mine").with(asUser())).andExpect(jsonPath("$.content[0].canCancel").value(true));
        mockMvc.perform(post(URL + "/" + id + "/cancel").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        expectError(mockMvc.perform(post(URL + "/" + id + "/cancel").with(asUser())), 409);
    }

    @Test
    void aTemplateWithoutApprovalIsApprovedAtOnce() throws Exception {
        String json = mockMvc.perform(post("/api/v1/templates").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sandbox\",\"engine\":\"TERRAFORM\",\"engineRef\":{\"organization\":\"tcg\",\"workspacePrefix\":\"sbx-\"},"
                                + "\"requiresApproval\":false,\"allowedRoles\":[\"USER\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sandbox = JsonPath.read(json, "$.templateId");

        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"" + sandbox + "\",\"environmentName\":\"sbx-one\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
}
