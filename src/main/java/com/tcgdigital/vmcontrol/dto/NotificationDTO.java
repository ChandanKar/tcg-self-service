package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;

import java.sql.Timestamp;

public class NotificationDTO {

    private String notificationId;
    private NotificationType type;
    private String title;
    private String message;
    private String entityType;
    private String entityId;
    private boolean isRead;
    private Timestamp createdAt;

    public static NotificationDTO from(Notification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.notificationId = n.getNotificationId();
        dto.type = n.getType();
        dto.title = n.getTitle();
        dto.message = n.getMessage();
        dto.entityType = n.getEntityType();
        dto.entityId = n.getEntityId();
        dto.isRead = n.isRead();
        dto.createdAt = n.getCreatedAt();
        return dto;
    }

    public String getNotificationId() { return notificationId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public boolean isRead() { return isRead; }
    public Timestamp getCreatedAt() { return createdAt; }
}
