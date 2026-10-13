package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.webhook.WebhookService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook subscriptions over HTTP (E34-T06): who may manage which, the secret shown once and stored
 * encrypted, SSRF checks at save time, and the switch. URLs use IP literals, so no DNS is needed.
 */
class WebhookControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/webhooks";
    private static final String PUBLIC = "https://93.184.216.34/hooks/vm";

    @Autowired private WebhookService webhookService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void webhooks(boolean on) {
        Object target = AopTestUtils.getTargetObject(webhookService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    @BeforeEach
    void on() {
        webhooks(true);
    }

    @AfterEach
    void off() {
        webhooks(false);
    }

    private static String body(String url, String environmentId) {
        return "{\"label\":\"ci\",\"url\":\"" + url + "\",\"eventTypes\":[\"environment.stopped\"]"
                + (environmentId == null ? "" : ",\"environmentId\":\"" + environmentId + "\"") + "}";
    }

    @Test
    void anAdminCreatesAGlobalWebhookAndSeesTheSecretOnce() throws Exception {
        String json = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(PUBLIC, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.webhook.urlHost").value("https://93.184.216.34"))
                .andExpect(jsonPath("$.webhook.eventTypes[0]").value("environment.stopped"))
                .andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(json, "$.secret");
        assertThat(secret).startsWith("whsec_");

        String listed = mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(secret).doesNotContain("/hooks/vm");
        String stored = jdbcTemplate.queryForObject("SELECT secret_enc FROM webhook_subscription", String.class);
        assertThat(stored).doesNotContain(secret.substring(6));
    }

    @Test
    void environmentAdminsManageTheirEnvironmentsWebhooksButNotGlobalOnes() throws Exception {
        Environment env = newEnvironment("Hooks");
        expectError(mockMvc.perform(post(URL).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(PUBLIC, null))), 403);
        String id = JsonPath.read(mockMvc.perform(post(URL).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(PUBLIC, env.getEnvironmentId())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.webhook.id");

        mockMvc.perform(put(URL + "/" + id).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventTypes\":[\"operation.completed\",\"environment.started\"],\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.eventTypes.length()").value(2));
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/" + id).with(asEnvAdmin())).andExpect(status().isOk());
    }

    @Test
    void internalUrlsUnknownEventsAndHttpAreRefused() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("https://169.254.169.254/latest/meta-data", null))), 400)
                .andExpect(jsonPath("$.message", containsString("private or reserved")));
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("https://10.0.0.5/hook", null))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("http://93.184.216.34/hook", null))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"x\",\"url\":\"" + PUBLIC + "\",\"eventTypes\":[\"vm.exploded\"]}")), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"x\",\"url\":\"" + PUBLIC + "\",\"eventTypes\":[\"ping\"]}")), 400);
    }

    @Test
    void switchedOffWebhooksAreNotFound() throws Exception {
        webhooks(false);
        expectError(mockMvc.perform(get(URL).with(asAdmin())), 404);
    }
}
