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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Template catalogue API (E32-T01): the switch, who may write, validation and who sees what. */
class TemplateControllerTest extends SecuredWebTestBase {

    @Autowired private SettingsService settings;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String URL = "/api/v1/templates";

    @BeforeEach
    void switchOn() {
        settings.set("provisioning.enabled", "true", admin.getUserId());
    }

    @AfterEach
    void switchOff() {
        settings.reset("provisioning.enabled", admin.getUserId());
    }

    private static String template(String name, String roles, String schema) {
        return "{\"name\":\"" + name + "\",\"description\":\"Dev stack\",\"engine\":\"SERVICE_CATALOG\","
                + "\"engineRef\":{\"productId\":\"prod-abc\",\"provisioningArtifactId\":\"pa-1\"},"
                + "\"parameterSchema\":" + schema + ",\"defaultTtlHours\":48,\"maxTtlHours\":168,"
                + "\"allowedRoles\":" + roles + ",\"estimatedMonthlyCost\":120.50}";
    }

    private static final String SCHEMA = "[{\"key\":\"size\",\"label\":\"Size\",\"type\":\"enum\",\"options\":[\"small\",\"large\"],\"default\":\"small\"},"
            + "{\"key\":\"owner\",\"type\":\"string\",\"required\":true,\"pattern\":\"[a-z.]+\"}]";

    private String create(String name, String roles) throws Exception {
        String json = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(template(name, roles, SCHEMA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.templateId");
    }

    @Test
    void anAdminCreatesATemplateThatIsListedForItsRolesAndAudited() throws Exception {
        String id = create("Dev stack", "[\"USER\"]");

        mockMvc.perform(get(URL).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].templateId").value(id))
                .andExpect(jsonPath("$[0].engineRef").doesNotExist())
                .andExpect(jsonPath("$[0].parameterSchema[0].key").value("size"));
        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(jsonPath("$[0].engineRef.productId").value("prod-abc"));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'TEMPLATE_CREATED'", Integer.class)).isEqualTo(1));
    }

    @Test
    void aUserDoesNotSeeTemplatesForOtherRolesOrRetiredOnes() throws Exception {
        create("Env admins only", "[\"ENV_ADMIN\"]");
        String retired = create("Old stack", "[\"USER\"]");
        mockMvc.perform(delete(URL + "/" + retired).with(asAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get(URL).with(asUser())).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(URL + "?includeRetired=true").with(asAdmin())).andExpect(jsonPath("$.length()").value(2));
        expectError(mockMvc.perform(get(URL + "/" + retired).with(asUser())), 404);
    }

    @Test
    void onlyAdminsChangeTheCatalogue() throws Exception {
        String id = create("Dev stack", "[\"USER\"]");

        mockMvc.perform(post(URL).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Other", "[\"USER\"]", SCHEMA))).andExpect(status().isForbidden());
        mockMvc.perform(put(URL + "/" + id).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Dev stack", "[\"USER\"]", SCHEMA))).andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/" + id).with(asViewer())).andExpect(status().isForbidden());
    }

    @Test
    void invalidTemplatesAreRefused() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Bad enum", "[\"USER\"]", "[{\"key\":\"size\",\"type\":\"enum\",\"options\":[]}]"))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Dup", "[\"USER\"]", "[{\"key\":\"a\"},{\"key\":\"a\"}]"))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Bad role", "[\"ROOT\"]", "[]"))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"No product\",\"engine\":\"SERVICE_CATALOG\",\"engineRef\":{\"provisioningArtifactId\":\"pa-1\"}}")), 400);
        create("Dev stack", "[\"USER\"]");
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("dev stack", "[\"USER\"]", "[]"))), 400);
    }

    @Test
    void turnedOffTheCatalogueIsNotFound() throws Exception {
        settings.reset("provisioning.enabled", admin.getUserId());

        expectError(mockMvc.perform(get(URL).with(asUser())), 404);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(template("Dev stack", "[\"USER\"]", SCHEMA))), 404);
    }
}
