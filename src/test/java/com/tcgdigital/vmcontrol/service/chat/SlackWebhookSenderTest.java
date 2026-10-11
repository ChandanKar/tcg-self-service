package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.hasSize;

/** Slack Block Kit payload, escaping, the single retry, and failures that hide the webhook URL (E24-T01). */
@ExtendWith(OutputCaptureExtension.class)
class SlackWebhookSenderTest {

    private static final String HOOK = "https://hooks.slack.com/services/T000/B000/SECRET-TOKEN";

    private MockRestServiceServer server;
    private SlackWebhookSender sender;
    private final List<Long> waits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new SlackWebhookSender(builder.build(), new ObjectMapper());
        sender.sleeper = waits::add;
    }

    static ChatMessage message() {
        return new ChatMessage("Lease ending: <Dev & QA>", "Stops in 30 min <@here>", ChatMessage.Severity.WARNING,
                List.of(new ChatMessage.Field("Environment", "dev-qa"), new ChatMessage.Field("Owner", "a&b")),
                List.of(new ChatAction(ChatActionType.EXTEND_LEASE, "Extend 2h", "https://app/x/a?t=1", "primary"),
                        new ChatAction(ChatActionType.STOP_ENVIRONMENT, "Stop now", "https://app/x/b?t=2", "danger")),
                "https://app/#/environments/e1");
    }

    @Test
    void blockKitWithTwoUrlButtonsAndEscapedText() {
        server.expect(once(), requestTo(HOOK)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.text").value("Lease ending: <Dev & QA>"))
                .andExpect(jsonPath("$.blocks[0].type").value("header"))
                .andExpect(jsonPath("$.blocks[0].text.text").value("Lease ending: <Dev & QA>"))
                .andExpect(jsonPath("$.blocks[1].text.text").value("Stops in 30 min &lt;@here&gt;"))
                .andExpect(jsonPath("$.blocks[2].fields", hasSize(2)))
                .andExpect(jsonPath("$.blocks[2].fields[1].text").value("*Owner*\na&amp;b"))
                .andExpect(jsonPath("$.blocks[3].type").value("actions"))
                .andExpect(jsonPath("$.blocks[3].elements", hasSize(2)))
                .andExpect(jsonPath("$.blocks[3].elements[0].url").value("https://app/x/a?t=1"))
                .andExpect(jsonPath("$.blocks[3].elements[0].style").value("primary"))
                .andExpect(jsonPath("$.blocks[3].elements[1].style").value("danger"))
                .andExpect(jsonPath("$.blocks[4].elements[0].text").value("Severity: WARNING · <https://app/#/environments/e1|Open in VM Control>"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(HOOK, message(), "#team-dev");
        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void a429IsRetriedOnceAfterRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        server.expect(once(), requestTo(HOOK)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        sender.send(HOOK, message(), "#team-dev");
        server.verify();
        assertThat(waits).containsExactly(AbstractWebhookSender.MAX_RETRY_WAIT_MS);
    }

    @Test
    void failuresNeverMentionTheWebhookUrl(CapturedOutput output) {
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(HOOK)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> sender.send(HOOK, message(), "#team-dev"))
                .isInstanceOf(ChatDeliveryException.class)
                .hasMessage("SLACK #team-dev webhook returned HTTP 502")
                .hasNoCause();

        server.reset();
        server.expect(once(), requestTo(HOOK)).andRespond(withSuccess("invalid_payload", MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> sender.send(HOOK, message())).isInstanceOf(ChatDeliveryException.class)
                .hasMessageNotContaining("hooks.slack.com");

        server.reset();
        server.expect(once(), requestTo(HOOK)).andRespond(request -> { throw new java.net.SocketTimeoutException(HOOK); });
        assertThatThrownBy(() -> sender.send(HOOK, message())).isInstanceOf(ChatDeliveryException.class)
                .hasMessage("SLACK webhook unreachable (SocketTimeoutException)");

        assertThat(output.getAll()).doesNotContain("SECRET-TOKEN").doesNotContain("hooks.slack.com");
        assertThatThrownBy(() -> sender.send(" ", message())).isInstanceOf(ChatDeliveryException.class);
    }

    @Test
    void retryAfterParsing() {
        assertThat(AbstractWebhookSender.retryAfterMs(null)).isEqualTo(1000);
        assertThat(AbstractWebhookSender.retryAfterMs("2")).isEqualTo(2000);
        assertThat(AbstractWebhookSender.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(1000);
        assertThat(AbstractWebhookSender.retryAfterMs("-3")).isZero();
    }
}
