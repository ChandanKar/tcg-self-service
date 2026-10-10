package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.NotificationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
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
    private final EmailService emailService;

    @Value("${notification.email.access-requested.enabled:false}")
    private boolean emailAccessRequestedEnabled;
    @Value("${notification.email.access-expiring.enabled:false}")
    private boolean emailAccessExpiringEnabled;
    @Value("${notification.email.access-expired.enabled:false}")
    private boolean emailAccessExpiredEnabled;
    @Value("${notification.email.access-granted.enabled:false}")
    private boolean emailAccessGrantedEnabled;
    @Value("${notification.email.access-request-approved.enabled:false}")
    private boolean emailAccessRequestApprovedEnabled;
    @Value("${notification.email.access-request-denied.enabled:false}")
    private boolean emailAccessRequestDeniedEnabled;
    @Value("${notification.email.access-revoked.enabled:false}")
    private boolean emailAccessRevokedEnabled;
    @Value("${notification.email.operation-failed.enabled:false}")
    private boolean emailOperationFailedEnabled;
    @Value("${notification.email.lock-broken.enabled:false}")
    private boolean emailLockBrokenEnabled;

    public NotificationService(NotificationRepository notificationRepository,
                               EnvironmentAccessRepository accessRepository,
                               UserRepository userRepository,
                               EmailService emailService) {
        this.notificationRepository = notificationRepository;
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
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

    /**
     * Unconditional bell receipt for a weekly digest report (Cost / Idle Waste / Rightsizing) —
     * fires regardless of whether that report's own email toggle is on, mirroring how every
     * other notification type always fires its bell independent of email delivery.
     */
    public void notifyWeeklyReportSent(String userId, NotificationType type, String title, String message) {
        create(userId, type, title, message, "ENVIRONMENT", null);
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

    /** A lock ran out (E07-T02): the holder and the environment's users can now act. */
    public void notifyLockExpiredForEnvironment(String environmentId, String environmentName, String holderUserId) {
        String holderName = resolveUserDisplayName(holderUserId);
        broadcastToEnvironment(environmentId, holderUserId,
                NotificationType.LOCK_EXPIRED,
                "Your lock expired: " + environmentName,
                "Your lock on environment \"" + environmentName + "\" expired and was released.",
                holderName + "'s lock expired: " + environmentName,
                holderName + "'s lock on environment \"" + environmentName + "\" expired; the environment is unlocked.",
                "ENVIRONMENT",
                environmentId);
    }

    public void notifyLockBrokenForEnvironment(String environmentId, String environmentName,
                                               String adminUserId, String originalHolderUserId,
                                               String reason) {
        String adminName = resolveUserDisplayName(adminUserId);
        String holderName = resolveUserDisplayName(originalHolderUserId);
        String details = isBlank(reason) ? "" : " Reason: " + reason;
        String actorTitle = "You broke the lock: " + environmentName;
        String actorMessage = "You broke " + holderName + "'s lock on environment \"" + environmentName + "\"." + details;
        String otherTitle = adminName + " broke the lock: " + environmentName;
        String otherMessage = adminName + " broke " + holderName + "'s lock on environment \"" + environmentName + "\"." + details;

        broadcastToEnvironment(environmentId, adminUserId,
                NotificationType.LOCK_BROKEN,
                actorTitle, actorMessage, otherTitle, otherMessage,
                "ENVIRONMENT",
                environmentId);

        if (emailLockBrokenEnabled) {
            emailBroadcastSplitByActor(resolveEnvironmentRecipients(environmentId), adminUserId,
                    actorTitle, actorMessage, otherTitle, otherMessage);
        }
    }

    public void notifyAccessGranted(String userId, String environmentName, String environmentId) {
        String title = "Access granted: " + environmentName;
        String message = "You have been granted access to environment \"" + environmentName + "\".";
        create(userId, NotificationType.ACCESS_GRANTED, title, message, "ENVIRONMENT", environmentId);
        if (emailAccessGrantedEnabled) {
            sendEventEmailToUser(userId, title, message);
        }
    }

    public void notifyAccessLevelChanged(String userId, String environmentName, String environmentId,
                                         AccessLevel previousLevel, AccessLevel newLevel) {
        String title = "Access level changed: " + environmentName;
        String message = "Your access to environment \"" + environmentName + "\" changed from "
                + previousLevel + " to " + newLevel + ".";
        create(userId, NotificationType.ACCESS_LEVEL_CHANGED, title, message, "ENVIRONMENT", environmentId);
        if (emailAccessGrantedEnabled) {
            sendEventEmailToUser(userId, title, message);
        }
    }

    public void notifyAccessRevoked(String userId, String environmentName, String environmentId) {
        String title = "Access revoked: " + environmentName;
        String message = "Your access to environment \"" + environmentName + "\" has been revoked.";
        create(userId, NotificationType.ACCESS_REVOKED, title, message, "ENVIRONMENT", environmentId);
        if (emailAccessRevokedEnabled) {
            sendEventEmailToUser(userId, title, message);
        }
    }

    public void notifyAccessRequestApproved(String userId, String environmentName, String environmentId) {
        String title = "Request approved: " + environmentName;
        String message = "Your access request for environment \"" + environmentName + "\" has been approved.";
        create(userId, NotificationType.ACCESS_REQUEST_APPROVED, title, message, "ENVIRONMENT", environmentId);
        if (emailAccessRequestApprovedEnabled) {
            sendEventEmailToUser(userId, title, message);
        }
    }

    public void notifyAccessRequestDenied(String userId, String environmentName, String environmentId) {
        String title = "Request denied: " + environmentName;
        String message = "Your access request for environment \"" + environmentName + "\" has been denied.";
        create(userId, NotificationType.ACCESS_REQUEST_DENIED, title, message, "ENVIRONMENT", environmentId);
        if (emailAccessRequestDeniedEnabled) {
            sendEventEmailToUser(userId, title, message);
        }
    }

    public void notifyAutomationRuleSkipped(String ruleOwnerUserId, String environmentName,
                                            String ruleName, String lockedByDisplayName) {
        create(ruleOwnerUserId, NotificationType.AUTOMATION_RULE_SKIPPED,
               "Automation rule skipped: " + ruleName,
               "Your automation rule \"" + ruleName + "\" on environment \"" + environmentName +
                       "\" was skipped because it is locked by " + lockedByDisplayName + ".",
               "ENVIRONMENT", null);
    }

    /** Tell every active admin that the system switched an automation rule off, and why (M16). */
    public void notifyAutomationRuleDisabled(String environmentName, String ruleName, String reason) {
        String title = "Automation rule disabled: " + ruleName;
        String message = "The automation rule \"" + ruleName + "\" on environment \"" + environmentName
                + "\" was disabled: " + reason + ".";
        userRepository.findByAdminTrueAndIsActiveTrue().forEach(admin ->
                create(admin.getUserId(), NotificationType.AUTOMATION_RULE_SKIPPED, title, message, "ENVIRONMENT", null));
    }

    public void notifyAccessRequestedForReviewers(String environmentId, String environmentName,
                                                  String requesterUserId, String requestId,
                                                  String requestedAccessLevel) {
        String requesterName = resolveUserDisplayName(requesterUserId);
        String level = isBlank(requestedAccessLevel) ? "access" : requestedAccessLevel.toUpperCase() + " access";
        String title = "Access request: " + environmentName;
        String message = requesterName + " requested " + level + " for environment \"" + environmentName + "\".";

        resolveEnvironmentReviewers(environmentId).forEach(user -> create(user.getUserId(),
                NotificationType.ACCESS_REQUESTED, title, message, "ACCESS_REQUEST", requestId));

        if (emailAccessRequestedEnabled) {
            // Email is intentionally narrower than the bell's reviewer set — All Admin only,
            // not env-admins (who are part of the bell's broadcast via resolveEnvironmentReviewers).
            sendEventEmail(userRepository.findByAdminTrueAndIsActiveTrue(), title, message);
        }
    }

    /**
     * Warn once per expiry: a warning created at or after {@code dedupSince} (the start of the
     * current warning window) suppresses another, while one from before an extension does not.
     */
    public void notifyAccessExpiring(String userId, String scopeLabel, String environmentId,
                                     String accessId, Timestamp expiresAt, Timestamp dedupSince) {
        String title = "Access expiring: " + scopeLabel;
        String message = "Your access to \"" + scopeLabel + "\" expires on " + formatDate(expiresAt) + ".";
        boolean created = createIfAbsentSince(userId, NotificationType.ACCESS_EXPIRING, title, message,
                "ACCESS", accessId, dedupSince);

        if (created && emailAccessExpiringEnabled) {
            List<User> recipients = new ArrayList<>();
            userRepository.findById(userId).ifPresent(recipients::add);
            recipients.addAll(resolveAdministeringEnvAdmins(environmentId));
            sendEventEmail(recipients, title, message);
        }
    }

    public void notifyAccessExpired(String userId, String scopeLabel, String environmentId,
                                    String accessId) {
        String title = "Access expired: " + scopeLabel;
        String message = "Your access to \"" + scopeLabel + "\" has expired.";
        boolean created = createIfAbsent(userId, NotificationType.ACCESS_EXPIRED, title, message, "ACCESS", accessId);

        if (created && emailAccessExpiredEnabled) {
            List<User> recipients = new ArrayList<>();
            userRepository.findById(userId).ifPresent(recipients::add);
            recipients.addAll(resolveAdministeringEnvAdmins(environmentId));
            sendEventEmail(recipients, title, message);
        }
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
        String actorTitle = "Your " + verb + " failed: " + environmentName;
        String actorMessage = "Your " + verb + " operation on \"" + environmentName + "\" failed. Reason: " + failureReason;
        String otherTitle = actorName + "'s " + verb + " failed: " + environmentName;
        String otherMessage = actorName + "'s " + verb + " operation on \"" + environmentName + "\" failed. Reason: " + failureReason;

        broadcastToEnvironment(environmentId, actorUserId,
                NotificationType.OPERATION_FAILED,
                actorTitle, actorMessage, otherTitle, otherMessage,
                "ENVIRONMENT",
                environmentId);

        if (emailOperationFailedEnabled) {
            // Env admin explicitly excluded from this email per spec, unlike the bell broadcast.
            emailBroadcastSplitByActor(resolveEnvironmentRecipients(environmentId, false), actorUserId,
                    actorTitle, actorMessage, otherTitle, otherMessage);
        }
    }

    /** Idle auto-stop stopped an environment (E16): its members can start it again from its page. */
    public void notifyIdleStopForEnvironment(String environmentId, String environmentName, long idleMinutes, int vmCount) {
        String title = "Idle auto-stop: " + environmentName;
        String message = "Stopped " + vmCount + " VM(s) after " + idleMinutes + " minutes idle. "
                + "Start it again from the environment page when you need it.";
        broadcastToEnvironment(environmentId, null, NotificationType.IDLE_AUTO_STOPPED,
                title, message, title, message, "ENVIRONMENT", environmentId);
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
        notifyEksSyncChanged(environmentId, environmentName, createdCount, updatedCount, removedCount, 0);
    }

    /**
     * @param failedCount node groups that failed to sync this cycle (e.g. a sequence-position
     *                    collision) — included so a cycle that fails on every item still
     *                    notifies, instead of looking identical to "nothing changed".
     */
    public void notifyEksSyncChanged(String environmentId, String environmentName,
                                     int createdCount, int updatedCount, int removedCount, int failedCount) {
        if (createdCount + updatedCount + removedCount + failedCount <= 0) {
            return;
        }
        String message = "EKS sync updated \"" + environmentName + "\": "
                + createdCount + " new, "
                + updatedCount + " changed, "
                + removedCount + " removed node group(s)"
                + (failedCount > 0 ? ", " + failedCount + " FAILED to sync (see audit log)." : ".");
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
        return resolveEnvironmentRecipients(environmentId, true);
    }

    /**
     * @param includeEnvAdmin when false, env-admin-flagged users are excluded entirely — both
     *                        from the direct-access-holder block (in case an env-admin also
     *                        happens to hold a direct grant on this environment) and from the
     *                        role-wide merge below. Used by Operation Failed, whose email spec
     *                        explicitly excludes env admin while the bell still includes them.
     */
    private List<User> resolveEnvironmentRecipients(String environmentId, boolean includeEnvAdmin) {
        Map<String, User> recipients = new LinkedHashMap<>();

        // Environment-wide notification — only ENVIRONMENT-scoped access holders. A user with
        // only GROUP grants is not an "environment member" for broadcast purposes.
        accessRepository.findActiveAccessWithUsersByEnvironment(environmentId).stream()
                .filter(EnvironmentAccess::isActive)
                .filter(ea -> ea.getScopeType() == AccessScopeType.ENVIRONMENT)
                .map(EnvironmentAccess::getUser)
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()))
                .filter(user -> includeEnvAdmin || !user.isEnvAdmin())
                .forEach(user -> recipients.put(user.getUserId(), user));

        userRepository.findByAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));
        if (includeEnvAdmin) {
            resolveAdministeringEnvAdmins(environmentId)
                    .forEach(user -> recipients.put(user.getUserId(), user));
        }

        return List.copyOf(recipients.values());
    }

    private List<User> resolveEnvironmentReviewers(String environmentId) {
        Map<String, User> recipients = new LinkedHashMap<>();

        accessRepository.findActiveAccessWithUsersByEnvironment(environmentId).stream()
                .filter(EnvironmentAccess::isActive)
                .filter(access -> access.getScopeType() == AccessScopeType.ENVIRONMENT)
                .filter(access -> access.getAccessLevel() == AccessLevel.ADMIN)
                .map(EnvironmentAccess::getUser)
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()))
                .forEach(user -> recipients.put(user.getUserId(), user));

        userRepository.findByAdminTrueAndIsActiveTrue()
                .forEach(user -> recipients.put(user.getUserId(), user));
        resolveAdministeringEnvAdmins(environmentId)
                .forEach(user -> recipients.put(user.getUserId(), user));

        return List.copyOf(recipients.values());
    }

    /**
     * Env-admin-flagged users who actually administer this specific environment (hold an
     * ADMIN-level {@link EnvironmentAccess} grant on it) — as opposed to the global {@code
     * envAdmin} role flag, which by itself says nothing about which environments a user is
     * scoped to. This is the fix for a prior bug where every env-admin was notified about
     * every environment platform-wide.
     */
    private List<User> resolveAdministeringEnvAdmins(String environmentId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return userRepository.findByEnvAdminTrueAndIsActiveTrue().stream()
                .filter(user -> accessRepository
                        .findByUserWithMinAccessLevel(user.getUserId(), AccessLevel.ADMIN, now).stream()
                        .anyMatch(access -> access.getEnvironment().getEnvironmentId().equals(environmentId)))
                .toList();
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

    /**
     * @return true if a new notification row was actually created (false if one already existed
     *         for this user/type/entity) — callers use this to avoid re-sending email every time
     *         a scheduled job re-evaluates an already-notified access grant.
     */
    private boolean createIfAbsentSince(String userId, NotificationType type, String title, String message,
                                        String entityType, String entityId, Timestamp since) {
        if (since == null) {
            return createIfAbsent(userId, type, title, message, entityType, entityId);
        }
        if (notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                userId, type, entityType, entityId, since)) {
            return false;
        }
        create(userId, type, title, message, entityType, entityId);
        return true;
    }

    private boolean createIfAbsent(String userId, NotificationType type, String title, String message,
                                String entityType, String entityId) {
        if (notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityId(
                userId, type, entityType, entityId)) {
            return false;
        }
        create(userId, type, title, message, entityType, entityId);
        return true;
    }

    // ============= Email dispatch helpers (Notification System v2) =============

    private void sendEventEmailToUser(String userId, String subject, String bodyText) {
        userRepository.findById(userId).ifPresent(user -> sendEventEmail(List.of(user), subject, bodyText));
    }

    /**
     * Admin-triggered "we are stopping this environment" broadcast (the "Notify &amp; Stop"
     * button). Unlike the other email types this isn't gated behind a passive
     * {@code notification.email.*.enabled} flag — the admin's click is itself the opt-in.
     * Notifies every active grant holder in-app and by email.
     *
     * @return how many people were emailed
     */
    public int notifyStopEnvironment(String environmentId, String environmentName, String actorUserId, String reason) {
        List<User> recipients = resolveEnvironmentRecipients(environmentId);
        String actorName = resolveUserDisplayName(actorUserId);
        String detail = isBlank(reason) ? "" : " Reason: " + reason;
        String actorTitle = "You're stopping: " + environmentName;
        String actorMessage = "You're stopping environment \"" + environmentName + "\"." + detail;
        String otherTitle = actorName + " is stopping: " + environmentName;
        String otherMessage = actorName + " is stopping environment \"" + environmentName + "\"." + detail;

        recipients.forEach(user -> {
            boolean isActor = user.getUserId().equals(actorUserId);
            create(user.getUserId(), NotificationType.ENVIRONMENT_STOP_NOTICE,
                    isActor ? actorTitle : otherTitle, isActor ? actorMessage : otherMessage,
                    "ENVIRONMENT", environmentId);
        });

        List<String> addresses = recipients.stream()
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()) && !isBlank(user.getEmail()))
                .map(User::getEmail)
                .distinct()
                .toList();
        if (!addresses.isEmpty()) {
            String subject = "Environment stop notice: " + environmentName;
            emailService.sendHtml(addresses, subject, EmailTemplates.eventEmail(subject, otherMessage), null, null,
                    "STOP_ENVIRONMENT_BROADCAST", environmentId, actorUserId);
        }
        return addresses.size();
    }

    private void sendEventEmail(List<User> recipients, String subject, String bodyText) {
        List<String> addresses = recipients.stream()
                .filter(user -> user != null && Boolean.TRUE.equals(user.getIsActive()) && !isBlank(user.getEmail()))
                .map(User::getEmail)
                .distinct()
                .toList();
        if (addresses.isEmpty()) {
            return;
        }
        emailService.sendHtml(addresses, subject, EmailTemplates.eventEmail(subject, bodyText), null, null);
    }

    /**
     * Splits a recipient list into "the actor" (one email, "your X" phrasing) and "everyone
     * else" (one batched email, "actor's X" phrasing) — mirrors the bell's per-recipient
     * actor/other title distinction, since email bodies can't vary per-address in one send.
     */
    private void emailBroadcastSplitByActor(List<User> recipients, String actorUserId,
                                            String actorTitle, String actorMessage,
                                            String otherTitle, String otherMessage) {
        List<User> actor = recipients.stream().filter(u -> u.getUserId().equals(actorUserId)).toList();
        List<User> others = recipients.stream().filter(u -> !u.getUserId().equals(actorUserId)).toList();
        if (!actor.isEmpty()) {
            sendEventEmail(actor, actorTitle, actorMessage);
        }
        if (!others.isEmpty()) {
            sendEventEmail(others, otherTitle, otherMessage);
        }
    }
}
