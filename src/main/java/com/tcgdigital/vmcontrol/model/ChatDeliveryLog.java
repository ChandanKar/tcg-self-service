package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/** One chat message attempt to one channel (E24-T03). */
@Entity
@Table(name = "chat_delivery_log")
public class ChatDeliveryLog {

    @Id
    @Column(name = "delivery_id", length = 36)
    private String deliveryId;

    @Column(name = "channel_id", nullable = false, length = 36)
    private String channelId;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "sent_at", nullable = false)
    private Timestamp sentAt;

    public ChatDeliveryLog() {
    }

    public ChatDeliveryLog(String deliveryId, String channelId, String notificationType, String environmentId,
                           String dedupKey, boolean success, String error, Timestamp sentAt) {
        this.deliveryId = deliveryId;
        this.channelId = channelId;
        this.notificationType = notificationType;
        this.environmentId = environmentId;
        this.dedupKey = dedupKey;
        this.success = success;
        this.error = error;
        this.sentAt = sentAt;
    }

    public String getDeliveryId() { return deliveryId; }
    public String getChannelId() { return channelId; }
    public String getNotificationType() { return notificationType; }
    public String getEnvironmentId() { return environmentId; }
    public String getDedupKey() { return dedupKey; }
    public Boolean getSuccess() { return success; }
    public String getError() { return error; }
    public Timestamp getSentAt() { return sentAt; }
}
