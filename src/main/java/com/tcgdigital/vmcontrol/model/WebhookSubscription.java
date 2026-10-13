package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/** A webhook subscription (E34-T06): global when environmentId is null. The signing secret is stored encrypted. */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    @Id
    @Column(name = "webhook_id", length = 36)
    private String webhookId;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "secret_enc", nullable = false, columnDefinition = "TEXT")
    private String secretEnc;

    /** Event type values, comma-separated. */
    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    private String eventTypes;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "disabled_reason", length = 255)
    private String disabledReason;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    public List<String> eventTypeList() {
        return eventTypes == null || eventTypes.isBlank() ? List.of() : Arrays.stream(eventTypes.split(",")).map(String::trim).toList();
    }

    public String getWebhookId() { return webhookId; }
    public void setWebhookId(String webhookId) { this.webhookId = webhookId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getEventTypes() { return eventTypes; }
    public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
