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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Queues and sends webhook deliveries (E34-T06). An event becomes one PENDING delivery per matching
 * subscription; the job sends due ones as a signed JSON POST and retries anything but a 2xx after
 * 1 min, 5 min, 30 min, 2 h and 12 h, then gives up. The URL is checked again before every send.
 * A subscription whose deliveries gave up 50 times in a row is disabled and its creator told.
 *
 * <p>Signature: {@code X-VmControl-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    /** Waits before the 2nd, 3rd, ... attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));
    static final int DISABLE_AFTER_GIVE_UPS = 50;
    private static final int BATCH = 50;

    @Value("${webhooks.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${webhooks.max-attempts:6}")
    private int maxAttempts;

    @Autowired(required = false)
    private NotificationService notificationService;

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final SafeUrlValidator urlValidator;
    private final WebhookTransport transport;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    public WebhookDeliveryService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                                  SafeUrlValidator urlValidator, WebhookTransport transport, SecretCipher cipher,
                                  ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.urlValidator = urlValidator;
        this.transport = transport;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The JSON body sent for an event. */
    String payload(PlatformEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id());
        body.put("type", event.type().value());
        body.put("occurredAt", event.occurredAt().toString());
        if (event.environmentId() != null) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("id", event.environmentId());
            env.put("name", event.environmentName());
            body.put("environment", env);
        } else {
            body.put("environment", null);
        }
        body.put("data", event.data());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise webhook payload", e);
        }
    }

    /** One PENDING delivery per active subscription of the event's environment (or global) that wants this type. */
    public int enqueue(PlatformEvent event) {
        String body = payload(event);
        int queued = 0;
        for (WebhookSubscription s : subscriptions.findActiveFor(event.environmentId())) {
            if (!s.eventTypeList().contains(event.type().value())) continue;
            WebhookDelivery d = new WebhookDelivery();
            d.setDeliveryId(UUID.randomUUID().toString());
            d.setWebhookId(s.getWebhookId());
            d.setEventId(event.id());
            d.setEventType(event.type().value());
            d.setPayload(body);
            d.setStatus(WebhookDelivery.Status.PENDING);
            d.setNextAttemptAt(Timestamp.from(clock.instant()));
            deliveries.save(d);
            queued++;
        }
        return queued;
    }

    /** Sends every due delivery; returns how many were delivered. */
    public int processDue() {
        int delivered = 0;
        for (WebhookDelivery d : deliveries.findDue(Timestamp.from(clock.instant()), PageRequest.of(0, BATCH))) {
            try {
                if (attempt(d)) delivered++;
            } catch (Exception e) {
                log.warn("Webhook delivery {} failed unexpectedly: {}", d.getDeliveryId(), e.getMessage());
            }
        }
        return delivered;
    }

    /** One attempt of a delivery: sent and recorded, retried later, or given up. */
    boolean attempt(WebhookDelivery d) {
        WebhookSubscription s = subscriptions.findById(d.getWebhookId()).orElse(null);
        Instant now = clock.instant();
        d.setAttempt(d.getAttempt() + 1);
        if (s == null || !s.isActive()) {
            finish(d, WebhookDelivery.Status.GAVE_UP, null, "The subscription is disabled", now);
            return false;
        }
        WebhookTransport.Response response;
        try {
            URI url = urlValidator.validate(s.getUrl());
            response = transport.post(url, headers(d, cipher.decrypt(s.getSecretEnc()), now), d.getPayload(),
                    Duration.ofMillis(timeoutMs));
        } catch (ValidationException e) {
            response = new WebhookTransport.Response(0, e.getMessage());
        }
        d.setResponseCode(response.status() == 0 ? null : response.status());
        if (response.ok()) {
            d.setDeliveredAt(Timestamp.from(now));
            finish(d, WebhookDelivery.Status.DELIVERED, null, null, now);
            if (s.getConsecutiveFailures() != 0) {
                s.setConsecutiveFailures(0);
                subscriptions.save(s);
            }
            return true;
        }
        String error = response.error() != null ? response.error() : "HTTP " + response.status();
        if (d.getAttempt() >= maxAttempts) {
            finish(d, WebhookDelivery.Status.GAVE_UP, null, error, now);
            s.setConsecutiveFailures(s.getConsecutiveFailures() + 1);
            if (s.getConsecutiveFailures() >= DISABLE_AFTER_GIVE_UPS) {
                s.setActive(false);
                s.setDisabledReason(DISABLE_AFTER_GIVE_UPS + " deliveries in a row failed");
                if (notificationService != null) {
                    notificationService.notifyWebhookDisabled(s.getCreatedBy(), s.getWebhookId(), s.getLabel(), s.getDisabledReason());
                }
            }
            subscriptions.save(s);
        } else {
            Duration wait = BACKOFF.get(Math.min(d.getAttempt() - 1, BACKOFF.size() - 1));
            finish(d, WebhookDelivery.Status.PENDING, Timestamp.from(now.plus(wait)), error, now);
        }
        return false;
    }

    /** Sends a ping to one subscription now and returns the receiver's answer (the subscription's Test). */
    public WebhookTransport.Response ping(WebhookSubscription s, PlatformEvent ping) {
        URI url = urlValidator.validate(s.getUrl());
        WebhookDelivery d = new WebhookDelivery();
        d.setDeliveryId(UUID.randomUUID().toString());
        d.setEventType(ping.type().value());
        return transport.post(url, headers(d, cipher.decrypt(s.getSecretEnc()), clock.instant(), payload(ping)),
                payload(ping), Duration.ofMillis(timeoutMs));
    }

    private void finish(WebhookDelivery d, WebhookDelivery.Status status, Timestamp next, String error, Instant now) {
        d.setStatus(status);
        d.setNextAttemptAt(next);
        d.setError(error);
        deliveries.save(d);
    }

    private Map<String, String> headers(WebhookDelivery d, String secret, Instant now) {
        return headers(d, secret, now, d.getPayload());
    }

    private Map<String, String> headers(WebhookDelivery d, String secret, Instant now, String body) {
        long t = now.getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-VmControl-Event", d.getEventType());
        headers.put("X-VmControl-Delivery", d.getDeliveryId());
        headers.put("X-VmControl-Signature", "t=" + t + ",v1=" + sign(secret, t, body));
        headers.put("User-Agent", "VmControl-Webhooks/1");
        return headers;
    }

    /** hex(HMAC-SHA256(secret, t + "." + body)). */
    public static String sign(String secret, long t, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
