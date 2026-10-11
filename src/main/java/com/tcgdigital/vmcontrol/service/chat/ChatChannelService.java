package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.dto.ChatChannelDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.ChatChannel;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.repository.ChatChannelRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Chat channels per environment, plus global defaults (E24-T02). Webhook URLs must be https on an
 * allow-listed host (chat.allowed-webhook-hosts, "*.x" for any subdomain of x); they are stored
 * encrypted and never returned or logged. Changes are audited without the URL.
 */
@Service
public class ChatChannelService {

    static final String TEST_TITLE = "Test message from VM Control";

    private final ChatChannelRepository channelRepository;
    private final EnvironmentRepository environmentRepository;
    private final SecretCipher cipher;
    private final ChatSenderRegistry senders;
    private final AuditService auditService;

    @Value("${chat.allowed-webhook-hosts:*.webhook.office.com,*.logic.azure.com,*.api.powerplatform.com}")
    private String allowedHosts = "";

    @Value("${chat.enabled:false}")
    private boolean chatEnabled;

    public ChatChannelService(ChatChannelRepository channelRepository, EnvironmentRepository environmentRepository,
                              SecretCipher cipher, ChatSenderRegistry senders, AuditService auditService) {
        this.channelRepository = channelRepository;
        this.environmentRepository = environmentRepository;
        this.cipher = cipher;
        this.senders = senders;
        this.auditService = auditService;
    }

    public ChatChannelDTO.Options options() {
        return new ChatChannelDTO.Options(chatEnabled,
                Arrays.stream(ChatProvider.values()).map(Enum::name).toList(),
                Arrays.stream(NotificationType.values()).map(Enum::name).toList());
    }

    /** The channel's environment (null for a global default), for authorization. */
    @Transactional(readOnly = true)
    public String environmentOf(String channelId) {
        return channel(channelId).getEnvironmentId();
    }

    @Transactional(readOnly = true)
    public List<ChatChannelDTO> list(String environmentId, boolean globalOnly) {
        List<ChatChannel> channels = environmentId != null ? channelRepository.findByEnvironmentIdOrderByLabelAsc(environmentId)
                : globalOnly ? channelRepository.findByEnvironmentIdIsNullOrderByLabelAsc()
                : channelRepository.findAllByOrderByLabelAsc();
        return channels.stream().map(ChatChannelService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ChatChannelDTO get(String channelId) {
        return toDto(channel(channelId));
    }

    @Transactional
    public ChatChannelDTO create(String actorId, ChatChannelDTO.Request request) {
        if (request == null) {
            throw new ValidationException("The channel is required");
        }
        String environmentId = blankToNull(request.environmentId());
        Environment environment = environmentId == null ? null : environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        if (request.webhookUrl() == null || request.webhookUrl().isBlank()) {
            throw new ValidationException("The webhook URL is required");
        }
        ChatChannel channel = new ChatChannel();
        channel.setChannelId(UUID.randomUUID().toString());
        channel.setEnvironmentId(environmentId);
        channel.setProvider(provider(request.provider()));
        channel.setLabel(label(request.label()));
        setWebhook(channel, request.webhookUrl());
        channel.setEventTypes(String.join(",", eventTypes(request.eventTypes())));
        channel.setIsActive(request.active() == null || request.active());
        channel.setCreatedBy(actorId);
        channel = channelRepository.save(channel);
        auditService.logAction(actorId, AuditAction.CHAT_CHANNEL_CREATED, "chat_channel", channel.getChannelId(),
                channel.getLabel(), describe(channel, environment));
        return toDto(channel);
    }

    @Transactional
    public ChatChannelDTO update(String channelId, String actorId, ChatChannelDTO.Request request) {
        ChatChannel channel = channel(channelId);
        if (request == null) {
            throw new ValidationException("The channel is required");
        }
        List<String> changes = new ArrayList<>();
        if (request.label() != null && !request.label().trim().equals(channel.getLabel())) {
            channel.setLabel(label(request.label()));
            changes.add("label");
        }
        if (request.provider() != null && provider(request.provider()) != channel.getProvider()) {
            channel.setProvider(provider(request.provider()));
            changes.add("provider");
        }
        if (request.webhookUrl() != null && !request.webhookUrl().isBlank()) {
            setWebhook(channel, request.webhookUrl());
            changes.add("webhook URL");
        }
        if (request.eventTypes() != null) {
            String events = String.join(",", eventTypes(request.eventTypes()));
            if (!events.equals(channel.getEventTypes())) {
                channel.setEventTypes(events);
                changes.add("events");
            }
        }
        if (request.active() != null && !request.active().equals(channel.getIsActive())) {
            channel.setIsActive(request.active());
            changes.add(request.active() ? "enabled" : "disabled");
        }
        channel = channelRepository.save(channel);
        if (!changes.isEmpty()) {
            auditService.logAction(actorId, AuditAction.CHAT_CHANNEL_UPDATED, "chat_channel", channelId, channel.getLabel(),
                    "Changed " + String.join(", ", changes));
        }
        return toDto(channel);
    }

    @Transactional
    public void delete(String channelId, String actorId) {
        ChatChannel channel = channel(channelId);
        channelRepository.delete(channel);
        auditService.logAction(actorId, AuditAction.CHAT_CHANNEL_DELETED, "chat_channel", channelId, channel.getLabel(),
                "Deleted " + channel.getProvider() + " channel " + channel.getLabel());
    }

    /** Sends a test message; the result never carries the URL. */
    @Transactional(readOnly = true)
    public ChatChannelDTO.TestResult sendTest(String channelId) {
        ChatChannel channel = channel(channelId);
        Environment environment = channel.getEnvironmentId() == null ? null
                : environmentRepository.findById(channel.getEnvironmentId()).orElse(null);
        ChatMessage message = new ChatMessage(TEST_TITLE,
                "This channel will receive " + (environment == null ? "default" : name(environment)) + " notifications.",
                ChatMessage.Severity.INFO,
                List.of(new ChatMessage.Field("Events", String.join(", ", channel.eventTypeList()))), List.of(), null);
        try {
            senders.get(channel.getProvider()).send(cipher.decrypt(channel.getWebhookUrlEnc()), message, channel.getLabel());
            return new ChatChannelDTO.TestResult(true, null);
        } catch (ChatDeliveryException | IllegalStateException | IllegalArgumentException e) {
            return new ChatChannelDTO.TestResult(false, e.getMessage());
        }
    }

    // ============= Validation =============

    private void setWebhook(ChatChannel channel, String webhookUrl) {
        String host = validateUrl(webhookUrl.trim());
        channel.setWebhookUrlEnc(cipher.encrypt(webhookUrl.trim()));
        channel.setWebhookHost(host);
    }

    /** https on an allow-listed host; returns the host. */
    String validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ValidationException("The webhook URL is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ValidationException("The webhook URL must start with https://");
        }
        if (uri.getUserInfo() != null) {
            throw new ValidationException("The webhook URL must not contain credentials");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!hostAllowed(host)) {
            throw new ValidationException("Webhooks on " + host + " are not allowed (chat.allowed-webhook-hosts)");
        }
        return host;
    }

    boolean hostAllowed(String host) {
        for (String raw : allowedHosts.split(",")) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) : host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static ChatProvider provider(String value) {
        if (value == null || value.isBlank()) {
            return ChatProvider.TEAMS;
        }
        try {
            return ChatProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unsupported chat provider: " + value);
        }
    }

    private static String label(String value) {
        String label = value == null ? "" : value.trim();
        if (label.isEmpty()) {
            throw new ValidationException("A channel name is required");
        }
        if (label.length() > 100) {
            throw new ValidationException("The channel name is longer than 100 characters");
        }
        return label;
    }

    private static List<String> eventTypes(List<String> values) {
        List<String> events = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(v -> v.trim().toUpperCase(Locale.ROOT)).filter(v -> !v.isEmpty()).distinct().toList();
        if (events.isEmpty()) {
            throw new ValidationException("Choose at least one event");
        }
        for (String event : events) {
            try {
                NotificationType.valueOf(event);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Unknown event: " + event);
            }
        }
        return events;
    }

    private ChatChannel channel(String channelId) {
        return channelRepository.findById(channelId).orElseThrow(() -> new ResourceNotFoundException("ChatChannel", channelId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    private static String describe(ChatChannel channel, Environment environment) {
        return "Added " + channel.getProvider() + " channel " + channel.getLabel() + " (" + channel.getWebhookHost() + ") for "
                + (environment == null ? "all environments (default)" : name(environment)) + ": " + channel.getEventTypes();
    }

    /** "prod-12.westeurope.logic.azure.com" -> "…westeurope.logic.azure.com". */
    static String maskHost(String host) {
        if (host == null) {
            return null;
        }
        // Hide the first label, which is the most specific part of the address.
        return "…" + (host.split("\\.").length > 2 ? host.substring(host.indexOf('.') + 1) : host);
    }

    private static ChatChannelDTO toDto(ChatChannel c) {
        return new ChatChannelDTO(c.getChannelId(), c.getEnvironmentId(), c.getProvider().name(), c.getLabel(),
                maskHost(c.getWebhookHost()), c.eventTypeList(), Boolean.TRUE.equals(c.getIsActive()), c.getCreatedBy(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
