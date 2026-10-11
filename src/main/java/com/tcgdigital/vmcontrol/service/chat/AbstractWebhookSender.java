package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

/**
 * Shared webhook POST for the chat senders (E24-T01): one retry on 429/5xx after Retry-After
 * (capped at 5 seconds), and failures that never mention the webhook URL.
 */
abstract class AbstractWebhookSender implements ChatSender {

    private static final Logger log = LoggerFactory.getLogger(AbstractWebhookSender.class);
    static final long MAX_RETRY_WAIT_MS = 5_000;
    private static final long DEFAULT_RETRY_WAIT_MS = 1_000;

    protected final RestClient restClient;
    protected final ObjectMapper objectMapper;
    /** Waits before the retry; tests replace it so they do not sleep. */
    LongConsumer sleeper = AbstractWebhookSender::sleep;

    protected AbstractWebhookSender(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** The provider's JSON for the message. */
    protected abstract ObjectNode payload(ChatMessage message);

    /** Whether a 2xx response body means the message was accepted. */
    protected boolean accepted(String body) {
        return true;
    }

    private record Result(int status, String body, String retryAfter) {}

    @Override
    public void send(String webhookUrl, ChatMessage message, String channelLabel) {
        String name = provider() + (channelLabel == null || channelLabel.isBlank() ? "" : " " + channelLabel);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new ChatDeliveryException(name + ": no webhook configured");
        }
        String json = payload(message).toString();
        Result result = post(webhookUrl, json, name);
        if (result.status() == 429 || result.status() >= 500) {
            long waitMs = retryAfterMs(result.retryAfter());
            log.info("{} webhook answered {}; retrying once in {} ms", name, result.status(), waitMs);
            sleeper.accept(waitMs);
            result = post(webhookUrl, json, name);
        }
        if (result.status() < 200 || result.status() >= 300) {
            log.warn("{} webhook rejected the message: HTTP {}", name, result.status());
            throw new ChatDeliveryException(name + " webhook returned HTTP " + result.status());
        }
        if (!accepted(result.body())) {
            log.warn("{} webhook did not accept the message", name);
            throw new ChatDeliveryException(name + " webhook did not accept the message");
        }
        log.debug("{} message delivered: {}", name, message.title());
    }

    private Result post(String webhookUrl, String json, String name) {
        try {
            return restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        String body;
                        try {
                            body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            body = "";
                        }
                        return new Result(status.value(), body, response.getHeaders().getFirst("Retry-After"));
                    });
        } catch (RestClientException e) {
            // The client's message quotes the URL: report only the kind of failure.
            String kind = e.getMostSpecificCause().getClass().getSimpleName();
            log.warn("{} webhook unreachable ({})", name, kind);
            throw new ChatDeliveryException(name + " webhook unreachable (" + kind + ")");
        }
    }

    static long retryAfterMs(String header) {
        if (header == null || header.isBlank()) {
            return DEFAULT_RETRY_WAIT_MS;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return Math.max(0, Math.min(MAX_RETRY_WAIT_MS, seconds * 1000));
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_WAIT_MS;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
