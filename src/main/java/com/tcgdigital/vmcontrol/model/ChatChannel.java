package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * A chat channel that receives an environment's notifications (E24-T02); environmentId null is
 * the global default. The webhook URL is stored encrypted.
 */
@Entity
@Table(name = "chat_channel")
public class ChatChannel {

    @Id
    @Column(name = "channel_id", length = 36)
    private String channelId;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private ChatProvider provider;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    /** base64(iv | AES-GCM ciphertext), see SecretCipher. */
    @Column(name = "webhook_url_enc", nullable = false, columnDefinition = "TEXT")
    private String webhookUrlEnc;

    @Column(name = "webhook_host", nullable = false, length = 255)
    private String webhookHost;

    /** Comma-separated NotificationType names. */
    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    private String eventTypes;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    public ChatChannel() {
    }

    public List<String> eventTypeList() {
        return eventTypes == null || eventTypes.isBlank() ? List.of()
                : Arrays.stream(eventTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public ChatProvider getProvider() { return provider; }
    public void setProvider(ChatProvider provider) { this.provider = provider; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getWebhookUrlEnc() { return webhookUrlEnc; }
    public void setWebhookUrlEnc(String webhookUrlEnc) { this.webhookUrlEnc = webhookUrlEnc; }
    public String getWebhookHost() { return webhookHost; }
    public void setWebhookHost(String webhookHost) { this.webhookHost = webhookHost; }
    public String getEventTypes() { return eventTypes; }
    public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
