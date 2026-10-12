package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatChannel;
import com.tcgdigital.vmcontrol.model.ChatDeliveryLog;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.repository.ChatChannelRepository;
import com.tcgdigital.vmcontrol.repository.ChatDeliveryLogRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Sends environment events to the mapped Teams channels (E24-T03). The environment's active
 * channels subscribed to the event type get it; when it has none, the subscribed global defaults
 * do. Messages leave only after the surrounding transaction commits (never on rollback), on the
 * notification executor, and a failing webhook never affects the caller: each attempt is recorded
 * in chat_delivery_log. Nothing happens unless chat.enabled.
 */
@Service
public class ChatDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ChatDispatchService.class);

    private final ChatChannelRepository channelRepository;
    private final ChatDeliveryLogRepository deliveryLogRepository;
    private final SecretCipher cipher;
    private final ChatSenderRegistry senders;
    private final AfterCommit afterCommit;
    private final Executor executor;

    @Value("${chat.enabled:false}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean live(String key, boolean current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    @Value("${chat.public-base-url:}")
    private String publicBaseUrl = "";

    public ChatDispatchService(ChatChannelRepository channelRepository, ChatDeliveryLogRepository deliveryLogRepository,
                               SecretCipher cipher, ChatSenderRegistry senders, AfterCommit afterCommit,
                               @Qualifier("notificationExecutor") Executor executor) {
        this.channelRepository = channelRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.cipher = cipher;
        this.senders = senders;
        this.afterCommit = afterCommit;
        this.executor = executor;
    }

    public boolean isEnabled() {
        return live("chat.enabled", enabled);
    }

    /** The app link for an environment, or null when chat.public-base-url is not set. */
    public String environmentLink(String environmentId) {
        return environmentId == null ? null : appLink("#/environments/" + environmentId);
    }

    /** An app page ("#/pending-requests"), or null when chat.public-base-url is not set. */
    public String appLink(String hash) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return null;
        }
        // /home serves the app; signed-out users are sent to /login, which keeps the hash (E24-T04).
        return publicBaseUrl.replaceAll("/+$", "") + "/home" + hash;
    }

    /** A button to add (E24-T04): it becomes a signed one-click link when links can be made. */
    public record ActionSpec(com.tcgdigital.vmcontrol.model.ChatActionType type, String label, String targetId, String style,
                             java.time.Duration ttl) {
        public ActionSpec(com.tcgdigital.vmcontrol.model.ChatActionType type, String label, String targetId, String style) {
            this(type, label, targetId, style, null);
        }
    }

    /**
     * Like {@link #notify(String, NotificationType, ChatMessage.Severity, String, String, String)}
     * with buttons. Each button is a single-use signed link to the confirm page; without
     * chat.public-base-url or a signing key the message goes out without them.
     */
    public void notifyWithActions(String environmentId, NotificationType type, ChatMessage.Severity severity, String title,
                                  String text, String linkHash, List<ActionSpec> actions, String dedupKey) {
        if (!live("chat.enabled", enabled)) {
            return;
        }
        java.util.List<ChatAction> buttons = new java.util.ArrayList<>();
        if (appLink("") != null && actionTokens != null && actionTokens.isConfigured()) {
            for (ActionSpec spec : actions) {
                try {
                    String token = spec.ttl() == null ? actionTokens.issue(spec.type(), environmentId, spec.targetId())
                            : actionTokens.issue(spec.type(), environmentId, spec.targetId(), spec.ttl());
                    buttons.add(new ChatAction(spec.type(), spec.label(), appLink("#/chat-action/" + token), spec.style()));
                } catch (RuntimeException e) {
                    log.warn("Could not create a {} chat link: {}", spec.type(), e.getClass().getSimpleName());
                }
            }
        }
        String link = linkHash != null ? appLink(linkHash) : environmentLink(environmentId);
        dispatch(environmentId, type, new ChatMessage(title, text, severity, List.of(), buttons, link), dedupKey);
    }

    /** Signs the one-click links (E24-T04); optional so the dispatcher works without it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatActionTokenService actionTokens;

    /** Builds the usual message (text plus an Open link) and dispatches it. */
    public void notify(String environmentId, NotificationType type, ChatMessage.Severity severity, String title, String text) {
        notify(environmentId, type, severity, title, text, null);
    }

    /** As {@link #notify(String, NotificationType, ChatMessage.Severity, String, String)}, sent once per dedupKey. */
    public void notify(String environmentId, NotificationType type, ChatMessage.Severity severity, String title, String text,
                       String dedupKey) {
        if (!live("chat.enabled", enabled)) {
            return;
        }
        dispatch(environmentId, type, new ChatMessage(title, text, severity, List.of(), List.of(), environmentLink(environmentId)),
                dedupKey);
    }

    public void dispatch(String environmentId, NotificationType type, ChatMessage message) {
        dispatch(environmentId, type, message, null);
    }

    /**
     * Queues the message for after commit. dedupKey (optional) makes a channel get it at most once,
     * e.g. one lease warning although every participant gets their own bell notification.
     */
    public void dispatch(String environmentId, NotificationType type, ChatMessage message, String dedupKey) {
        if (!live("chat.enabled", enabled)) {
            return;
        }
        try {
            afterCommit.run(() -> executor.execute(() -> deliver(environmentId, type, message, dedupKey)));
        } catch (RuntimeException e) {
            log.warn("Could not queue a {} chat message: {}", type, e.getClass().getSimpleName());
        }
    }

    /** The channels that get this event: the environment's subscribed ones, else the subscribed defaults. */
    List<ChatChannel> channelsFor(String environmentId, NotificationType type) {
        List<ChatChannel> own = environmentId == null ? List.of()
                : channelRepository.findByEnvironmentIdAndIsActiveTrue(environmentId).stream()
                        .filter(c -> c.eventTypeList().contains(type.name())).toList();
        if (!own.isEmpty()) {
            return own;
        }
        return channelRepository.findByEnvironmentIdIsNullAndIsActiveTrue().stream()
                .filter(c -> c.eventTypeList().contains(type.name())).toList();
    }

    void deliver(String environmentId, NotificationType type, ChatMessage message, String dedupKey) {
        List<ChatChannel> channels;
        try {
            channels = channelsFor(environmentId, type);
        } catch (RuntimeException e) {
            log.warn("Could not look up chat channels for {}: {}", type, e.getClass().getSimpleName());
            return;
        }
        for (ChatChannel channel : channels) {
            if (dedupKey != null && deliveryLogRepository.existsByChannelIdAndDedupKeyAndSuccessTrue(channel.getChannelId(), dedupKey)) {
                continue;
            }
            boolean success = false;
            String error = null;
            try {
                senders.get(channel.getProvider()).send(cipher.decrypt(channel.getWebhookUrlEnc()), message, channel.getLabel());
                success = true;
            } catch (ChatDeliveryException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                // Never the exception message: client errors can quote the webhook URL.
                error = e.getClass().getSimpleName();
                log.warn("Chat message to {} failed: {}", channel.getLabel(), error);
            }
            try {
                deliveryLogRepository.save(new ChatDeliveryLog(UUID.randomUUID().toString(), channel.getChannelId(), type.name(),
                        environmentId, dedupKey, success, error == null ? null : truncate(error),
                        new Timestamp(System.currentTimeMillis())));
            } catch (RuntimeException e) {
                log.warn("Could not record a chat delivery to {}: {}", channel.getLabel(), e.getClass().getSimpleName());
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
