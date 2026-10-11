package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Teams Adaptive Card payload, the single retry on 429/5xx, and failures that never reveal the
 * webhook URL (E24-T01); the registry resolves the sender.
 */
@ExtendWith(OutputCaptureExtension.class)
class TeamsWebhookSenderTest {

    private static final String HOOK = "https://prod.westeurope.logic.azure.com/workflows/abc/triggers/manual?sig=SECRET-SIG";

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

    static ChatMessage message() {
        return new ChatMessage("Lease ending: <Dev & QA>", "Stops in 30 min", ChatMessage.Severity.WARNING,
                List.of(new ChatMessage.Field("Environment", "dev-qa"), new ChatMessage.Field("Owner", "a&b")),
                List.of(new ChatAction(ChatActionType.EXTEND_LEASE, "Extend 2h", "https://app/x/a?t=1", "primary"),
                        new ChatAction(ChatActionType.STOP_ENVIRONMENT, "Stop now", "https://app/x/b?t=2", "danger")),
                "https://app/#/environments/e1");
    }

    @Test
    void anAdaptiveCardWithTwoOpenUrlActions() {
        server.expect(once(), requestTo(HOOK)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/vnd.microsoft.card.adaptive"))
                .andExpect(jsonPath("$.attachments[0].content.type").value("AdaptiveCard"))
                .andExpect(jsonPath("$.attachments[0].content.version").value("1.4"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].text").value("Lease ending: <Dev & QA>"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].color").value("Warning"))
                .andExpect(jsonPath("$.attachments[0].content.body[1].text").value("Stops in 30 min"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].type").value("FactSet"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts", hasSize(2)))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts[1].value").value("a&b"))
                .andExpect(jsonPath("$.attachments[0].content.body[3].text").value("[Open in VM Control](https://app/#/environments/e1)"))
                .andExpect(jsonPath("$.attachments[0].content.actions", hasSize(2)))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].type").value("Action.OpenUrl"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].url").value("https://app/x/a?t=1"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].style").value("positive"))
                .andExpect(jsonPath("$.attachments[0].content.actions[1].type").value("Action.OpenUrl"))
                .andExpect(jsonPath("$.attachments[0].content.actions[1].style").value("destructive"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        sender.send(HOOK, message(), "Platform channel");
        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void a429IsRetriedOnceAfterRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.OK));

        sender.send(HOOK, message(), "Platform channel");
        server.verify();
        assertThat(waits).containsExactly(AbstractWebhookSender.MAX_RETRY_WAIT_MS);
    }

    @Test
    void failuresNeverMentionTheWebhookUrl(CapturedOutput output) {
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> sender.send(HOOK, message(), "Platform channel"))
                .isInstanceOf(ChatDeliveryException.class)
                .hasMessage("TEAMS Platform channel webhook returned HTTP 503")
                .hasNoCause();
        assertThat(waits).containsExactly(1000L);

        server.reset();
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> sender.send(HOOK, message())).isInstanceOf(ChatDeliveryException.class)
                .hasMessage("TEAMS webhook returned HTTP 400");
        assertThat(waits).hasSize(1); // 4xx other than 429 is not retried

        server.reset();
        server.expect(once(), requestTo(HOOK)).andRespond(request -> { throw new java.net.SocketTimeoutException(HOOK); });
        assertThatThrownBy(() -> sender.send(HOOK, message())).isInstanceOf(ChatDeliveryException.class)
                .hasMessage("TEAMS webhook unreachable (SocketTimeoutException)");

        assertThat(output.getAll()).doesNotContain("SECRET-SIG").doesNotContain("logic.azure.com");
        assertThatThrownBy(() -> sender.send(" ", message())).isInstanceOf(ChatDeliveryException.class);
    }

    @Test
    void retryAfterParsing() {
        assertThat(AbstractWebhookSender.retryAfterMs(null)).isEqualTo(1000);
        assertThat(AbstractWebhookSender.retryAfterMs("2")).isEqualTo(2000);
        assertThat(AbstractWebhookSender.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(1000);
        assertThat(AbstractWebhookSender.retryAfterMs("-3")).isZero();
    }

    @Test
    void theRegistryResolvesTheSender() {
        ChatSenderRegistry registry = new ChatSenderRegistry(List.of(sender));
        assertThat(registry.get(ChatProvider.TEAMS)).isSameAs(sender);
        assertThatThrownBy(() -> new ChatSenderRegistry(List.of()).get(ChatProvider.TEAMS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
