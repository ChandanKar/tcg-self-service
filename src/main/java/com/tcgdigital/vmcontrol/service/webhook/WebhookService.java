package com.tcgdigital.vmcontrol.service.webhook;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.WebhookDelivery;
import com.tcgdigital.vmcontrol.model.WebhookSubscription;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.WebhookDeliveryRepository;
import com.tcgdigital.vmcontrol.repository.WebhookSubscriptionRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.chat.SecretCipher;
import com.tcgdigital.vmcontrol.service.events.PlatformEvent;
import com.tcgdigital.vmcontrol.service.events.PlatformEventType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Webhook subscriptions (E34-T06): admins manage global ones, environment admins those of their
 * environments. The URL must pass {@link SafeUrlValidator}; the signing secret is made here, shown
 * once and stored encrypted with the secrets-at-rest key (CHAT_SECRET_KEY). Off (webhooks.enabled)
 * the API answers 404 and nothing is queued or sent.
 */
@Service
public class WebhookService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${webhooks.enabled:false}")
    private boolean enabled;

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final EnvironmentRepository environments;
    private final SafeUrlValidator urlValidator;
    private final SecretCipher cipher;
    private final WebhookDeliveryService deliveryService;
    private final SecurityService securityService;
    private final AuditService auditService;

    public WebhookService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                          EnvironmentRepository environments, SafeUrlValidator urlValidator, SecretCipher cipher,
                          WebhookDeliveryService deliveryService, SecurityService securityService, AuditService auditService) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.environments = environments;
        this.urlValidator = urlValidator;
        this.cipher = cipher;
        this.deliveryService = deliveryService;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @PostConstruct
    void checkKey() {
        if (enabled && !cipher.isConfigured()) {
            throw new IllegalStateException("webhooks.enabled=true needs the secrets-at-rest key CHAT_SECRET_KEY (chat.secret-key)");
        }
    }

    public boolean isEnabled() {
        return enabled && cipher.isConfigured();
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ResourceNotFoundException("Webhooks are not enabled");
        }
    }

    public record LastDelivery(String status, Timestamp at, Integer responseCode) {
    }

    public record WebhookView(String id, String label, String urlHost, List<String> eventTypes, String environmentId,
                              boolean active, String disabledReason, Timestamp createdAt, LastDelivery lastDelivery) {
    }

    public record CreateRequest(String label, String url, List<String> eventTypes, String environmentId) {
    }

    public record UpdateRequest(String label, String url, List<String> eventTypes, Boolean active) {
    }

    public record Created(String secret, WebhookView webhook) {
    }

    public record DeliveryView(String id, String eventId, String eventType, String status, int attempt, Integer responseCode,
                               String error, Timestamp createdAt, Timestamp nextAttemptAt, Timestamp deliveredAt) {
        static DeliveryView of(WebhookDelivery d) {
            return new DeliveryView(d.getDeliveryId(), d.getEventId(), d.getEventType(), d.getStatus().name(), d.getAttempt(),
                    d.getResponseCode(), d.getError(), d.getCreatedAt(), d.getNextAttemptAt(), d.getDeliveredAt());
        }
    }

    /** The subscriptions the caller may manage, optionally of one environment. */
    public List<WebhookView> list(String environmentId) {
        requireEnabled();
        List<WebhookSubscription> all = environmentId != null ? subscriptions.findByEnvironmentIdOrderByCreatedAtDesc(environmentId)
                : subscriptions.findAllByOrderByCreatedAtDesc();
        return all.stream().filter(this::mayManage).map(this::view).toList();
    }

    public Created create(CreateRequest request, String actorUserId) {
        requireEnabled();
        if (request == null) throw new ValidationException("A webhook needs a label, a URL and event types");
        String environmentId = blankToNull(request.environmentId());
        if (environmentId != null && environments.findById(environmentId).isEmpty()) {
            throw new ResourceNotFoundException("Environment", environmentId);
        }
        assertMayManage(environmentId);
        WebhookSubscription s = new WebhookSubscription();
        s.setWebhookId(UUID.randomUUID().toString());
        s.setLabel(label(request.label()));
        s.setUrl(url(request.url()));
        s.setEventTypes(String.join(",", eventTypes(request.eventTypes())));
        s.setEnvironmentId(environmentId);
        s.setCreatedBy(actorUserId);
        String secret = "whsec_" + secret();
        s.setSecretEnc(cipher.encrypt(secret));
        subscriptions.saveAndFlush(s);
        auditService.logAction(actorUserId, AuditAction.WEBHOOK_CREATED, "WEBHOOK", s.getWebhookId(), s.getLabel(),
                "Host " + host(s.getUrl()) + ", events " + s.getEventTypes() + (environmentId == null ? ", global" : ", environment " + environmentId));
        return new Created(secret, view(s));
    }

    public WebhookView update(String id, UpdateRequest request, String actorUserId) {
        WebhookSubscription s = managed(id);
        if (request.label() != null) s.setLabel(label(request.label()));
        if (request.url() != null && !request.url().isBlank()) s.setUrl(url(request.url()));
        if (request.eventTypes() != null) s.setEventTypes(String.join(",", eventTypes(request.eventTypes())));
        if (request.active() != null) {
            s.setActive(request.active());
            if (request.active()) {
                s.setConsecutiveFailures(0);
                s.setDisabledReason(null);
            }
        }
        subscriptions.saveAndFlush(s);
        auditService.logAction(actorUserId, AuditAction.WEBHOOK_UPDATED, "WEBHOOK", id, s.getLabel(),
                "Host " + host(s.getUrl()) + ", events " + s.getEventTypes() + ", " + (s.isActive() ? "active" : "disabled"));
        return view(s);
    }

    public void delete(String id, String actorUserId) {
        WebhookSubscription s = managed(id);
        subscriptions.delete(s);
        auditService.logAction(actorUserId, AuditAction.WEBHOOK_DELETED, "WEBHOOK", id, s.getLabel(), "Host " + host(s.getUrl()));
    }

    /** Sends a ping now and reports the receiver's answer. */
    public Map<String, Object> test(String id) {
        WebhookSubscription s = managed(id);
        WebhookTransport.Response r = deliveryService.ping(s, PlatformEvent.of(PlatformEventType.PING, s.getEnvironmentId(), null,
                Map.of("webhookId", s.getWebhookId())));
        return Map.of("delivered", r.ok(), "status", r.status(), "error", r.error() == null ? "" : r.error());
    }

    public Page<DeliveryView> deliveries(String id, Pageable pageable) {
        managed(id);
        return deliveries.findByWebhookIdOrderByCreatedAtDesc(id, pageable).map(DeliveryView::of);
    }

    private WebhookSubscription managed(String id) {
        requireEnabled();
        WebhookSubscription s = subscriptions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Webhook", id));
        if (!mayManage(s)) {
            throw new ResourceNotFoundException("Webhook", id);
        }
        return s;
    }

    private boolean mayManage(WebhookSubscription s) {
        return s.getEnvironmentId() == null ? securityService.isAdmin() : securityService.canAdministerEnvironment(s.getEnvironmentId());
    }

    private void assertMayManage(String environmentId) {
        boolean allowed = environmentId == null ? securityService.isAdmin() : securityService.canAdministerEnvironment(environmentId);
        if (!allowed) {
            throw new UnauthorizedException(environmentId == null ? "Only admins manage global webhooks"
                    : "You do not administer this environment");
        }
    }

    private WebhookView view(WebhookSubscription s) {
        WebhookDelivery last = deliveries.findFirstByWebhookIdOrderByCreatedAtDesc(s.getWebhookId());
        return new WebhookView(s.getWebhookId(), s.getLabel(), host(s.getUrl()), s.eventTypeList(), s.getEnvironmentId(),
                s.isActive(), s.getDisabledReason(), s.getCreatedAt(),
                last == null ? null : new LastDelivery(last.getStatus().name(),
                        last.getDeliveredAt() != null ? last.getDeliveredAt() : last.getCreatedAt(), last.getResponseCode()));
    }

    /** scheme://host[:port]; the path and query can hold a receiver's own secret, so they are never shown. */
    static String host(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "(invalid)";
        }
    }

    private static String label(String label) {
        String l = label == null ? "" : label.trim();
        if (l.isEmpty() || l.length() > 100) throw new ValidationException("Label must be 1 to 100 characters");
        return l;
    }

    private String url(String url) {
        if (url == null || url.length() > 2000) throw new ValidationException("The webhook URL is missing or too long");
        return urlValidator.validate(url).toString();
    }

    private static Set<String> eventTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) throw new ValidationException("Choose at least one event type");
        Set<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            PlatformEventType type = PlatformEventType.fromValue(value == null ? "" : value.trim())
                    .filter(t -> t != PlatformEventType.PING)
                    .orElseThrow(() -> new ValidationException("Unknown event type: " + value));
            result.add(type.value());
        }
        return result;
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
