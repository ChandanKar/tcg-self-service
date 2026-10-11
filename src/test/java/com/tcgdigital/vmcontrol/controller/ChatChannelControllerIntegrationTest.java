package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.ChatChannelRepository;
import com.tcgdigital.vmcontrol.service.chat.ChatChannelService;
import com.tcgdigital.vmcontrol.service.chat.ChatDeliveryException;
import com.tcgdigital.vmcontrol.service.chat.ChatMessage;
import com.tcgdigital.vmcontrol.service.chat.ChatSender;
import com.tcgdigital.vmcontrol.service.chat.ChatSenderRegistry;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat channels (E24-T02): role matrix, URL rules, masked output, encrypted storage, test message. */
class ChatChannelControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/chat-channels";
    private static final String HOOK = "https://prod-12.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=SECRET-SIG";

    @Autowired private ChatChannelRepository channels;
    @Autowired private ChatChannelService channelService;
    @Autowired private AuditLogRepository auditLogs;

    private Object serviceTarget;
    private Object realSenders;

    @AfterEach
    void restoreSenders() {
        if (serviceTarget != null) {
            ReflectionTestUtils.setField(serviceTarget, "senders", realSenders);
        }
    }

    private ChatSender mockSender() {
        serviceTarget = AopTestUtils.getTargetObject(channelService);
        realSenders = ReflectionTestUtils.getField(serviceTarget, "senders");
        ChatSender sender = Mockito.mock(ChatSender.class);
        when(sender.provider()).thenReturn(ChatProvider.TEAMS);
        ReflectionTestUtils.setField(serviceTarget, "senders", new ChatSenderRegistry(List.of(sender)));
        return sender;
    }

    private static String body(String envId, String hook) {
        return "{\"environmentId\":" + (envId == null ? "null" : "\"" + envId + "\"") + ",\"provider\":\"TEAMS\","
                + "\"label\":\"Platform channel\",\"webhookUrl\":\"" + hook + "\",\"eventTypes\":[\"LEASE_EXPIRING\",\"cost_anomaly\"]}";
    }

    @Test
    void anEnvironmentAdminAddsAChannelThatIsStoredEncryptedAndShownMasked() throws Exception {
        Environment env = newEnvironment("Chatty");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.ADMIN);

        String created = mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(env.getEnvironmentId(), HOOK)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedHost").value("…westeurope.logic.azure.com"))
                .andExpect(jsonPath("$.eventTypes[1]").value("COST_ANOMALY"))
                .andExpect(jsonPath("$.webhookUrl").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET-SIG"))))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.channelId");

        mockMvc.perform(get(URL).param("environmentId", env.getEnvironmentId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channelId").value(id))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("logic.azure.com/workflows"))));
        String stored = channels.findById(id).orElseThrow().getWebhookUrlEnc();
        assertThat(stored).doesNotContain("logic.azure.com").doesNotContain("SECRET-SIG");

        // Rename and disable; the URL is kept.
        mockMvc.perform(put(URL + "/" + id).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Renamed\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Renamed"))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(channels.findById(id).orElseThrow().getWebhookUrlEnc()).isEqualTo(stored);

        mockMvc.perform(delete(URL + "/" + id).with(asUser())).andExpect(status().isNoContent());
        awaitAsync(() -> assertThat(auditLogs.findAll())
                .filteredOn(a -> a.getAction() == AuditAction.CHAT_CHANNEL_CREATED
                        || a.getAction() == AuditAction.CHAT_CHANNEL_UPDATED || a.getAction() == AuditAction.CHAT_CHANNEL_DELETED)
                .hasSize(3)
                .allSatisfy(a -> assertThat(a.getDetails()).doesNotContain("SECRET-SIG").doesNotContain("/workflows/")));
    }

    @Test
    void badUrlsAndBadEventsAre400() throws Exception {
        String env = newEnvironment("Strict").getEnvironmentId();
        for (String hook : List.of("http://prod-1.westeurope.logic.azure.com/x", "https://evil.example.com/hook",
                "https://logic.azure.com.evil.io/x", "not a url", "https://user:pw@x.webhook.office.com/a")) {
            expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(env, hook))), 400);
        }
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentId\":\"" + env + "\",\"label\":\"x\",\"webhookUrl\":\"" + HOOK + "\",\"eventTypes\":[\"NOPE\"]}")), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentId\":\"" + env + "\",\"label\":\"x\",\"webhookUrl\":\"" + HOOK + "\",\"eventTypes\":[]}")), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentId\":\"" + env + "\",\"provider\":\"SLACK\",\"label\":\"x\",\"webhookUrl\":\"" + HOOK
                        + "\",\"eventTypes\":[\"LEASE_EXPIRING\"]}")), 400);
        assertThat(channels.count()).isZero();
    }

    @Test
    void roleMatrix() throws Exception {
        String env = newEnvironment("Guarded").getEnvironmentId();
        String other = newEnvironment("Other").getEnvironmentId();
        grantEnv(operator, other, AccessLevel.ADMIN);
        grantEnv(viewer, env, AccessLevel.USER);

        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body(env, HOOK)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(body(env, HOOK)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("environmentId", env).with(asViewer())).andExpect(status().isForbidden());
        // Global defaults: admins only.
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body(null, HOOK)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(null, HOOK)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environmentId").doesNotExist());
        mockMvc.perform(get(URL).param("global", "true").with(asAdmin())).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/options").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0]").value("TEAMS"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void sendTestReportsDeliveryWithoutTheUrl() throws Exception {
        ChatSender sender = mockSender();
        String env = newEnvironment("Tested").getEnvironmentId();
        String created = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(env, HOOK)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.channelId");

        mockMvc.perform(post(URL + "/" + id + "/test").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true));
        ArgumentCaptor<ChatMessage> message = ArgumentCaptor.forClass(ChatMessage.class);
        verify(sender).send(eq(HOOK), message.capture(), eq("Platform channel"));
        assertThat(message.getValue().title()).isEqualTo("Test message from VM Control");

        doThrow(new ChatDeliveryException("TEAMS Platform channel webhook returned HTTP 404"))
                .when(sender).send(any(), any(), any());
        mockMvc.perform(post(URL + "/" + id + "/test").with(asAdmin()))
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.error").value("TEAMS Platform channel webhook returned HTTP 404"));
        mockMvc.perform(post(URL + "/" + id + "/test").with(asUser())).andExpect(status().isForbidden());
    }
}
