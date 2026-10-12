package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.UUID;

/** A user's choice for one notification type (E29-T05, table from V1): bell and email, both on by default. */
@Entity
@Table(name = "user_notification_preference")
public class UserNotificationPreference {

    @Id
    @Column(name = "preference_id", length = 36)
    private String preferenceId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    public UserNotificationPreference() {
    }

    public UserNotificationPreference(String userId, NotificationType type) {
        this.preferenceId = UUID.randomUUID().toString();
        this.userId = userId;
        this.notificationType = type;
    }

    public String getPreferenceId() { return preferenceId; }
    public String getUserId() { return userId; }
    public NotificationType getNotificationType() { return notificationType; }
    public boolean isInAppEnabled() { return inAppEnabled; }
    public void setInAppEnabled(boolean inAppEnabled) { this.inAppEnabled = inAppEnabled; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
