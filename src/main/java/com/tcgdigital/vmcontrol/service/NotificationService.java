package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.NotificationDTO;
import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Page<NotificationDTO> getNotifications(String userId, int page, int size) {
        return notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(NotificationDTO::from);
    }

    public Page<NotificationDTO> getUnreadNotifications(String userId, int page, int size) {
        return notificationRepository
                .findByUserIdAndIsReadOrderByCreatedAtDesc(userId, false, PageRequest.of(page, size))
                .map(NotificationDTO::from);
    }

    public long getUnreadCount(String userId) {
        return notificationRepository.countByUserIdAndIsRead(userId, false);
    }

    @Transactional
    public NotificationDTO markAsRead(String notificationId, String userId) {
        Notification n = notificationRepository.findById(notificationId)
                .filter(notif -> notif.getUserId().equals(userId))
                .orElseThrow(() -> new RuntimeException("Notification not found"));
        n.setRead(true);
        return NotificationDTO.from(notificationRepository.save(n));
    }

    @Transactional
    public int markAllAsRead(String userId) {
        return notificationRepository.markAllReadForUser(userId);
    }

    public void notifyLockBroken(String lockHolderUserId, String environmentName,
                                  String brokenByName, String reason) {
        create(lockHolderUserId,
               NotificationType.LOCK_BROKEN,
               "Lock broken: " + environmentName,
               "An admin (" + brokenByName + ") has removed your lock on environment \"" +
                       environmentName + "\". Reason: " + reason,
               "ENVIRONMENT", null);
    }

    public void notifyAccessGranted(String userId, String environmentName, String environmentId) {
        create(userId, NotificationType.ACCESS_GRANTED,
               "Access granted: " + environmentName,
               "You have been granted access to environment \"" + environmentName + "\".",
               "ENVIRONMENT", environmentId);
    }

    public void notifyAccessRevoked(String userId, String environmentName, String environmentId) {
        create(userId, NotificationType.ACCESS_REVOKED,
               "Access revoked: " + environmentName,
               "Your access to environment \"" + environmentName + "\" has been revoked.",
               "ENVIRONMENT", environmentId);
    }

    public void notifyAccessRequestApproved(String userId, String environmentName, String environmentId) {
        create(userId, NotificationType.ACCESS_REQUEST_APPROVED,
               "Request approved: " + environmentName,
               "Your access request for environment \"" + environmentName + "\" has been approved.",
               "ENVIRONMENT", environmentId);
    }

    public void notifyAccessRequestDenied(String userId, String environmentName, String environmentId) {
        create(userId, NotificationType.ACCESS_REQUEST_DENIED,
               "Request denied: " + environmentName,
               "Your access request for environment \"" + environmentName + "\" has been denied.",
               "ENVIRONMENT", environmentId);
    }

    public void notifyOperationCompleted(String userId, String environmentName, String operationType) {
        create(userId, NotificationType.OPERATION_COMPLETED,
               operationType + " completed: " + environmentName,
               "The " + operationType.toLowerCase() + " operation on environment \"" +
                       environmentName + "\" finished successfully.",
               "ENVIRONMENT", null);
    }

    public void notifyOperationFailed(String userId, String environmentName, String operationType, String reason) {
        create(userId, NotificationType.OPERATION_FAILED,
               operationType + " failed: " + environmentName,
               "The " + operationType.toLowerCase() + " operation on environment \"" +
                       environmentName + "\" failed. Reason: " + reason,
               "ENVIRONMENT", null);
    }

    private void create(String userId, NotificationType type, String title, String message,
                        String entityType, String entityId) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID().toString());
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        notificationRepository.save(n);
        log.debug("Notification created for user {}: {}", userId, title);
    }
}
