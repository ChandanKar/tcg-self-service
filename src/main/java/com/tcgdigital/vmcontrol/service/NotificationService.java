package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.NotificationDTO;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EnvironmentAccessRepository accessRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               EnvironmentAccessRepository accessRepository,
                               UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
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

    public void notifyLockAcquiredForEnvironment(String environmentId, String environmentName,
                                                 String actorUserId, String reason) {
        String actorName = resolveUserDisplayName(actorUserId);
        String details = isBlank(reason) ? "" : " Reason: " + reason;
        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.LOCK_ACQUIRED,
                "You acquired a lock: " + environmentName,
                "You acquired a lock on environment \"" + environmentName + "\"." + details,
                actorName + " acquired a lock: " + environmentName,
                actorName + " acquired a lock on environment \"" + environmentName + "\"." + details,
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyLockReleasedForEnvironment(String environmentId, String environmentName,
                                                 String actorUserId) {
        String actorName = resolveUserDisplayName(actorUserId);
        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.LOCK_RELEASED,
                "You released the lock: " + environmentName,
                "You released the lock on environment \"" + environmentName + "\".",
                actorName + " released the lock: " + environmentName,
                actorName + " released the lock on environment \"" + environmentName + "\".",
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyLockBrokenForEnvironment(String environmentId, String environmentName,
                                               String adminUserId, String originalHolderUserId,
                                               String reason) {
        String adminName = resolveUserDisplayName(adminUserId);
        String holderName = resolveUserDisplayName(originalHolderUserId);
        String details = isBlank(reason) ? "" : " Reason: " + reason;
        broadcastToEnvironment(environmentId, adminUserId,
                NotificationType.LOCK_BROKEN,
                "You broke the lock: " + environmentName,
                "You broke " + holderName + "'s lock on environment \"" + environmentName + "\"." + details,
                adminName + " broke the lock: " + environmentName,
                adminName + " broke " + holderName + "'s lock on environment \"" + environmentName + "\"." + details,
                "ENVIRONMENT",
                environmentId);
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

    public void notifyAccessRequestedForReviewers(String environmentId, String environmentName,
                                                  String requesterUserId, String requestId,
                                                  String requestedAccessLevel) {
        String requesterName = resolveUserDisplayName(requesterUserId);
        String level = isBlank(requestedAccessLevel) ? "access" : requestedAccessLevel.toUpperCase() + " access";
        resolveEnvironmentReviewers(environmentId).forEach(user -> create(user.getUserId(),
                NotificationType.ACCESS_REQUESTED,
                "Access request: " + environmentName,
                requesterName + " requested " + level + " for environment \"" + environmentName + "\".",
                "ACCESS_REQUEST",
                requestId));
    }

    public void notifyAccessExpiring(String userId, String environmentName, String environmentId,
                                     String accessId, Timestamp expiresAt) {
        createIfAbsent(userId, NotificationType.ACCESS_EXPIRING,
                "Access expiring: " + environmentName,
                "Your access to environment \"" + environmentName + "\" expires on " + formatDate(expiresAt) + ".",
                "ACCESS",
                accessId);
    }

    public void notifyAccessExpired(String userId, String environmentName, String environmentId,
                                    String accessId) {
        createIfAbsent(userId, NotificationType.ACCESS_EXPIRED,
                "Access expired: " + environmentName,
                "Your access to environment \"" + environmentName + "\" has expired.",
                "ACCESS",
                accessId);
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

    public void notifyOperationRequestedForEnvironment(String environmentId, String environmentName,
                                                       String actorUserId, String operationType,
                                                       String scopeLabel, int targetCount) {
        String actorName = resolveUserDisplayName(actorUserId);
        String verb = operationVerb(operationType);
        String targetSummary = targetCount == 1 ? "1 target" : targetCount + " targets";
        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.OPERATION_REQUESTED,
                "You requested " + verb + ": " + environmentName,
                "You requested " + verb + " for " + scopeLabel + " in \"" + environmentName + "\" (" + targetSummary + ").",
                actorName + " requested " + verb + ": " + environmentName,
                actorName + " requested " + verb + " for " + scopeLabel + " in \"" + environmentName + "\" (" + targetSummary + ").",
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyOperationCompletedForEnvironment(String environmentId, String environmentName,
                                                       String actorUserId, String operationType,
                                                       int totalTargets, int failedTargets) {
        String actorName = resolveUserDisplayName(actorUserId);
        String verb = operationVerb(operationType);
        String result = failedTargets > 0
                ? "finished with " + failedTargets + " failure(s) out of " + totalTargets + " target(s)"
                : "completed successfully for " + totalTargets + " target(s)";
        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.OPERATION_COMPLETED,
                "Your " + verb + " completed: " + environmentName,
                "Your " + verb + " operation on \"" + environmentName + "\" " + result + ".",
                actorName + "'s " + verb + " completed: " + environmentName,
                actorName + "'s " + verb + " operation on \"" + environmentName + "\" " + result + ".",
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyOperationFailedForEnvironment(String environmentId, String environmentName,
                                                    String actorUserId, String operationType,
                                                    String reason) {
        String actorName = resolveUserDisplayName(actorUserId);
        String verb = operationVerb(operationType);
        String failureReason = isBlank(reason) ? "Unknown error" : reason;
        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.OPERATION_FAILED,
                "Your " + verb + " failed: " + environmentName,
                "Your " + verb + " operation on \"" + environmentName + "\" failed. Reason: " + failureReason,
                actorName + "'s " + verb + " failed: " + environmentName,
                actorName + "'s " + verb + " operation on \"" + environmentName + "\" failed. Reason: " + failureReason,
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyStateDriftDetected(String environmentId, String environmentName,
                                         String vmName, String previousStatus, String currentStatus,
                                         String vmId) {
        broadcastToEnvironment(environmentId, null,
                NotificationType.STATE_DRIFT_DETECTED,
                "State drift detected: " + environmentName,
                "State drift detected for \"" + vmName + "\": " + previousStatus + " -> " + currentStatus + ".",
                "State drift detected: " + environmentName,
                "State drift detected for \"" + vmName + "\": " + previousStatus + " -> " + currentStatus + ".",
                "VM",
                vmId);
    }

    public void notifyEksSyncChanged(String environmentId, String environmentName,
                                     int createdCount, int updatedCount, int removedCount) {
        int totalChanges = createdCount + updatedCount + removedCount;
        if (totalChanges <= 0) {
            return;
        }
        String message = "EKS sync updated \"" + environmentName + "\": "
                + createdCount + " new, "
                + updatedCount + " changed, "
                + removedCount + " removed node group(s).";
        broadcastToEnvironment(environmentId, null,
                NotificationType.EKS_SYNC_CHANGED,
                "EKS sync changes: " + environmentName,
                message,
                "EKS sync changes: " + environmentName,
                message,
                "ENVIRONMENT",
                environmentId);
    }

    private void broadcastToEnvironment(String environmentId, String actorUserId,
                                        NotificationType type,
                                        String actorTitle, String actorMessage,
                                        String otherTitle, String otherMessage,
                                        String entityType, String entityId) {
        resolveEnvironmentRecipients(environmentId).forEach(user -> {
            boolean isActor = user.getUserId().equals(actorUserId);
            create(user.getUserId(), type,
                    isActor ? actorTitle : otherTitle,
                    isActor ? actorMessage : otherMessage,
                    entityType,
                    entityId);
        });
    }

    private List<User> resolveEnvironmentRecipients(String environmentId) {
        Map<String, User> recipients = new LinkedHashMap<>();

        accessRepository.findActiveAccessWithUsersByEnvironment(environmentId).stream()
                .filter(EnvironmentAccess::isActive)
                .map(EnvironmentAccess::getUser)
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()))
                .forEach(user -> recipients.put(user.getUserId(), user));

        userRepository.findByAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));
        userRepository.findByEnvAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));

        return List.copyOf(recipients.values());
    }

    private List<User> resolveEnvironmentReviewers(String environmentId) {
        Map<String, User> recipients = new LinkedHashMap<>();

        accessRepository.findActiveAccessWithUsersByEnvironment(environmentId).stream()
                .filter(EnvironmentAccess::isActive)
                .filter(access -> access.getAccessLevel() == com.tcgdigital.vmcontrol.model.AccessLevel.ADMIN)
                .map(EnvironmentAccess::getUser)
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()))
                .forEach(user -> recipients.put(user.getUserId(), user));

        userRepository.findByAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));
        userRepository.findByEnvAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));

        return List.copyOf(recipients.values());
    }

    private String resolveUserDisplayName(String userId) {
        return userRepository.findById(userId)
                .map(user -> firstNonBlank(user.getDisplayName(), user.getEmail(), user.getUsername(), user.getUserId()))
                .orElse(firstNonBlank(userId, "Someone"));
    }

    private String operationVerb(String operationType) {
        return "STOP".equalsIgnoreCase(operationType) ? "stop" : "start";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String formatDate(Timestamp timestamp) {
        if (timestamp == null) {
            return "soon";
        }
        return timestamp.toLocalDateTime().toLocalDate().toString();
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

    private void createIfAbsent(String userId, NotificationType type, String title, String message,
                                String entityType, String entityId) {
        if (notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityId(
                userId, type, entityType, entityId)) {
            return;
        }
        create(userId, type, title, message, entityType, entityId);
    }
}
