package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/** One event queued for one subscription (E34-T06): PENDING until delivered, retried with backoff, then GAVE_UP. */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    public enum Status { PENDING, DELIVERED, FAILED, GAVE_UP }

    @Id
    @Column(name = "delivery_id", length = 36)
    private String deliveryId;

    @Column(name = "webhook_id", nullable = false, length = 36)
    private String webhookId;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "next_attempt_at")
    private Timestamp nextAttemptAt;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "delivered_at")
    private Timestamp deliveredAt;

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }
    public String getWebhookId() { return webhookId; }
    public void setWebhookId(String webhookId) { this.webhookId = webhookId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Timestamp getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Timestamp nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Integer getResponseCode() { return responseCode; }
    public void setResponseCode(Integer responseCode) { this.responseCode = responseCode; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Timestamp deliveredAt) { this.deliveredAt = deliveredAt; }
}
