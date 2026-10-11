package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** Teams Adaptive Card payload, retry and failure (E24-T01); the registry resolves both senders. */
class TeamsWebhookSenderTest {

    private static final String HOOK = "https://prod.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=SECRET";

    private MockRestServiceServer server;
    private TeamsWebhookSender sender;
    private final List<Long> waits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new TeamsWebhookSender(builder.build(), new ObjectMapper());
        sender.sleeper = waits::add;
    }

    @Test
    void anAdaptiveCardWithTwoOpenUrlActions() {
        server.expect(once(), requestTo(HOOK))
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/vnd.microsoft.card.adaptive"))
                .andExpect(jsonPath("$.attachments[0].content.type").value("AdaptiveCard"))
                .andExpect(jsonPath("$.attachments[0].content.version").value("1.4"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].text").value("Lease ending: <Dev & QA>"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].color").value("Warning"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].type").value("FactSet"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts[0].title").value("Environment"))
                .andExpect(jsonPath("$.attachments[0].content.actions", hasSize(2)))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].type").value("Action.OpenUrl"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].url").value("https://app/x/a?t=1"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].style").value("positive"))
                .andExpect(jsonPath("$.attachments[0].content.actions[1].style").value("destructive"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        sender.send(HOOK, SlackWebhookSenderTest.message(), "Platform channel");
        server.verify();
    }

    @Test
    void a503IsRetriedOnceThenFailsWithoutTheUrl() {
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> sender.send(HOOK, SlackWebhookSenderTest.message()))
                .isInstanceOf(ChatDeliveryException.class)
                .hasMessage("TEAMS webhook returned HTTP 503");
        assertThat(waits).containsExactly(1000L);

        server.reset();
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> sender.send(HOOK, SlackWebhookSenderTest.message())).isInstanceOf(ChatDeliveryException.class);
        assertThat(waits).hasSize(1); // 4xx other than 429 is not retried
    }

    @Test
    void theRegistryResolvesEachProvider() {
        SlackWebhookSender slack = new SlackWebhookSender(RestClient.create(), new ObjectMapper());
        ChatSenderRegistry registry = new ChatSenderRegistry(List.of(slack, sender));
        assertThat(registry.get(ChatProvider.SLACK)).isSameAs(slack);
        assertThat(registry.get(ChatProvider.TEAMS)).isSameAs(sender);
        assertThatThrownBy(() -> new ChatSenderRegistry(List.of(slack)).get(ChatProvider.TEAMS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
