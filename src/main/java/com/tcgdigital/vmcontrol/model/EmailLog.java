package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * A record of one attempted email send — weekly reports, access-lifecycle emails,
 * stop-environment broadcasts, etc. Written by {@link com.tcgdigital.vmcontrol.service.EmailService}
 * for every real send attempt (the master {@code notification.email.enabled} flag off means
 * nothing was attempted, so nothing is logged). Answers "who have we emailed and when" from
 * the admin UI instead of only the app log.
 */
@Entity
@Table(name = "email_log")
public class EmailLog {

    @Id
    @Column(name = "log_id", length = 36)
    private String logId;

    /** Free-text label for what triggered the send, e.g. "WEEKLY_IDLE_WASTE_REPORT",
     *  "STOP_ENVIRONMENT_BROADCAST". Null for calls that don't supply one. */
    @Column(name = "notification_type", length = 50)
    private String notificationType;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    /** Comma-separated recipient addresses. */
    @Column(name = "recipients", columnDefinition = "TEXT", nullable = false)
    private String recipients;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Admin who triggered the send, for an admin-initiated email (e.g. stop-env broadcast).
     *  Null for scheduled/automated sends. */
    @Column(name = "sent_by_user_id", length = 36)
    private String sentByUserId;

    @Column(name = "sent_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp sentAt;

    public EmailLog() {
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getRecipients() {
        return recipients;
    }

    public void setRecipients(String recipients) {
        this.recipients = recipients;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public void setRecipientCount(int recipientCount) {
        this.recipientCount = recipientCount;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSentByUserId() {
        return sentByUserId;
    }

    public void setSentByUserId(String sentByUserId) {
        this.sentByUserId = sentByUserId;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }

    public void setSentAt(Timestamp sentAt) {
        this.sentAt = sentAt;
    }
}
