package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.EmailLog;

import java.sql.Timestamp;

/**
 * DTO for {@link EmailLog} — the admin-facing "who have we emailed" list.
 */
public class EmailLogDTO {

    private String logId;
    private String notificationType;
    private String environmentId;
    private String recipients;
    private int recipientCount;
    private String subject;
    private boolean success;
    private String errorMessage;
    private String sentByUserId;
    private Timestamp sentAt;

    public static EmailLogDTO fromEntity(EmailLog log) {
        EmailLogDTO dto = new EmailLogDTO();
        dto.logId = log.getLogId();
        dto.notificationType = log.getNotificationType();
        dto.environmentId = log.getEnvironmentId();
        dto.recipients = log.getRecipients();
        dto.recipientCount = log.getRecipientCount();
        dto.subject = log.getSubject();
        dto.success = log.isSuccess();
        dto.errorMessage = log.getErrorMessage();
        dto.sentByUserId = log.getSentByUserId();
        dto.sentAt = log.getSentAt();
        return dto;
    }

    public String getLogId() {
        return logId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getRecipients() {
        return recipients;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSentByUserId() {
        return sentByUserId;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }
}
