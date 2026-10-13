package com.tcgdigital.vmcontrol.service.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.WebhookDelivery;
import com.tcgdigital.vmcontrol.model.WebhookSubscription;
import com.tcgdigital.vmcontrol.repository.WebhookDeliveryRepository;
import com.tcgdigital.vmcontrol.repository.WebhookSubscriptionRepository;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.chat.SecretCipher;
import com.tcgdigital.vmcontrol.service.events.PlatformEvent;
import com.tcgdigital.vmcontrol.service.events.PlatformEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Queueing, signing, retrying and giving up on webhook deliveries (E34-T06). */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final String SECRET = "whsec_test-secret";

    @Mock private WebhookSubscriptionRepository subscriptions;
    @Mock private WebhookDeliveryRepository deliveries;
    @Mock private SafeUrlValidator validator;
    @Mock private WebhookTransport transport;
    @Mock private SecretCipher cipher;
    @Mock private NotificationService notifications;

    private WebhookDeliveryService service;
    private WebhookSubscription sub;
    private final List<WebhookDelivery> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new WebhookDeliveryService(subscriptions, deliveries, validator, transport, cipher, new ObjectMapper());
        ReflectionTestUtils.setField(service, "timeoutMs", 5000L);
        ReflectionTestUtils.setField(service, "maxAttempts", 6);
        ReflectionTestUtils.setField(service, "notificationService", notifications);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        sub = new WebhookSubscription();
        sub.setWebhookId("w-1");
        sub.setLabel("ci");
        sub.setUrl("https://hooks.example.com/in");
        sub.setSecretEnc("enc");
        sub.setEventTypes("environment.stopped,operation.completed");
        sub.setEnvironmentId("env-1");
        sub.setCreatedBy("u-1");
        lenient().when(cipher.decrypt("enc")).thenReturn(SECRET);
        lenient().when(subscriptions.findById("w-1")).thenReturn(Optional.of(sub));
        lenient().when(validator.validate(anyString())).thenAnswer(inv -> URI.create(inv.getArgument(0)));
        lenient().when(deliveries.save(any(WebhookDelivery.class))).thenAnswer(inv -> { saved.add(inv.getArgument(0)); return inv.getArgument(0); });
    }

    private WebhookDelivery queued() {
        when(subscriptions.findActiveFor("env-1")).thenReturn(List.of(sub));
        service.enqueue(PlatformEvent.of(PlatformEventType.ENVIRONMENT_STOPPED, "env-1", "qa", Map.of("operationId", "op-1")));
        return saved.get(saved.size() - 1);
    }

    @Test
    void onlyMatchingEventTypesAreQueuedWithTheDocumentedPayload() throws Exception {
        when(subscriptions.findActiveFor("env-1")).thenReturn(List.of(sub));

        assertThat(service.enqueue(PlatformEvent.of(PlatformEventType.ACCESS_GRANTED, "env-1", "qa", Map.of()))).isZero();
        assertThat(service.enqueue(PlatformEvent.of(PlatformEventType.ENVIRONMENT_STOPPED, "env-1", "qa", Map.of("operationId", "op-1")))).isEqualTo(1);

        WebhookDelivery d = saved.get(0);
        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
        var body = new ObjectMapper().readTree(d.getPayload());
        assertThat(body.path("type").asText()).isEqualTo("environment.stopped");
        assertThat(body.path("environment").path("name").asText()).isEqualTo("qa");
        assertThat(body.path("data").path("operationId").asText()).isEqualTo("op-1");
        assertThat(body.path("id").asText()).isEqualTo(d.getEventId());
    }

    @Test
    void aDeliveryIsSignedWithTheDocumentedAlgorithmAndRecordedDelivered() {
        WebhookDelivery d = queued();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        when(transport.post(any(), headers.capture(), anyString(), any())).thenReturn(new WebhookTransport.Response(204, null));

        assertThat(service.attempt(d)).isTrue();

        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.DELIVERED);
        assertThat(d.getDeliveredAt()).isNotNull();
        String signature = headers.getValue().get("X-VmControl-Signature");
        long t = NOW.getEpochSecond();
        assertThat(signature).isEqualTo("t=" + t + ",v1=" + WebhookDeliveryService.sign(SECRET, t, d.getPayload()));
        // An independent HMAC over "t.body" agrees.
        assertThat(signature).endsWith(hmacHex(SECRET, t + "." + d.getPayload()));
        assertThat(headers.getValue()).containsEntry("X-VmControl-Event", "environment.stopped")
                .containsEntry("X-VmControl-Delivery", d.getDeliveryId());
    }

    @Test
    void failuresAreRetriedOnTheBackoffScheduleAndThenGivenUp() {
        WebhookDelivery d = queued();
        when(transport.post(any(), any(), anyString(), any())).thenReturn(new WebhookTransport.Response(500, null));
        List<Duration> expected = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofHours(12));

        for (Duration wait : expected) {
            assertThat(service.attempt(d)).isFalse();
            assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
            assertThat(d.getNextAttemptAt().toInstant()).isEqualTo(NOW.plus(wait));
            assertThat(d.getResponseCode()).isEqualTo(500);
        }
        service.attempt(d);

        assertThat(d.getAttempt()).isEqualTo(6);
        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.GAVE_UP);
        assertThat(d.getNextAttemptAt()).isNull();
        assertThat(sub.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aUrlThatNowResolvesInternallyIsNotCalled() {
        WebhookDelivery d = queued();
        when(validator.validate(anyString())).thenThrow(new ValidationException("The webhook host resolves to a private or reserved address"));

        service.attempt(d);

        verify(transport, never()).post(any(), any(), anyString(), any());
        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
        assertThat(d.getError()).contains("private or reserved");
    }

    @Test
    void fiftyGiveUpsInARowDisableTheSubscriptionAndTellItsCreator() {
        sub.setConsecutiveFailures(49);
        WebhookDelivery d = queued();
        d.setAttempt(5);
        when(transport.post(any(), any(), anyString(), any())).thenReturn(new WebhookTransport.Response(0, "Connection refused"));

        service.attempt(d);

        assertThat(sub.isActive()).isFalse();
        assertThat(sub.getDisabledReason()).contains("50");
        verify(notifications).notifyWebhookDisabled(eq("u-1"), eq("w-1"), eq("ci"), anyString());
    }

    @Test
    void aDisabledOrDeletedSubscriptionGivesUpWithoutSending() {
        WebhookDelivery d = queued();
        sub.setActive(false);

        service.attempt(d);

        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.Status.GAVE_UP);
        verifyNoInteractions(transport);
    }

    private static String hmacHex(String secret, String text) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
