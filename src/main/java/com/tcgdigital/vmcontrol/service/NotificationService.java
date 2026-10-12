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

    /** Each user's choices of bell and email per type (E29-T05); absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotificationPreferenceService preferences;


    /** Live settings (E29-T02); absent in plain unit tests, where each toggle's default applies. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean setting(String key, boolean defaultValue) {
        return settings == null ? defaultValue : settings.getBoolean(key, defaultValue);
    }



    public NotificationService(NotificationRepository notificationRepository,
                               EnvironmentAccessRepository accessRepository,
                               UserRepository userRepository,
                               EmailService emailService) {
        this.notificationRepository = notificationRepository;
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    // ============= Chat (E24-T03) =============

    /** Teams channels for environment events; set by Spring, null in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat;

    @Value("${chat.actions.stop-enabled:false}")
    private boolean chatStopActionsEnabled;

    @Value("${jira.enabled:false}")
    private boolean jiraEnabled;

    void setChatDispatch(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat) {
        this.chat = chat;
    }

    /** Sends the third-person wording to the environment's chat channels after commit; never throws. */
    private void toChat(String environmentId, NotificationType type, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity severity,
                        String title, String text) {
        toChat(environmentId, type, severity, title, text, null);
    }

    private void toChat(String environmentId, NotificationType type, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity severity,
                        String title, String text, String dedupKey) {
        if (chat == null) {
            return;
        }
        try {
            chat.notify(environmentId, type, severity, title, text, dedupKey);
        } catch (RuntimeException e) {
            log.warn("Could not send {} to chat: {}", type, e.getClass().getSimpleName());
        }
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
        // Missing and someone else's look the same: a 404, never a 500 (E11-T08).
        Notification n = notificationRepository.findById(notificationId)
                .filter(notif -> notif.getUserId().equals(userId))
                .orElseThrow(() -> new com.tcgdigital.vmcontrol.exception.ResourceNotFoundException("Notification", notificationId));
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

    /**
     * Warn a lock holder that their lock expires soon (E07-T03). At most one warning per
     * environment since {@code since} (the later of expiry minus the threshold and the lock's
     * start), so each threshold warns once and an extension re-arms the warnings.
     *
     * @return true if a warning was created
     */
    public boolean notifyLockExpiring(String holderUserId, String environmentId, String environmentName,
                                      java.time.Instant expiresAt, java.time.ZoneId zone, Timestamp since) {
        String at = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(expiresAt.atZone(zone));
        return createIfAbsentSince(holderUserId, NotificationType.LOCK_EXPIRING,
                "Your lock expires at " + at + ": " + environmentName,
                "Your lock on " + environmentName + " expires at " + at + ". Extend it from the environment page.",
                "ENVIRONMENT", environmentId, since);
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
        toChat(environmentId, NotificationType.LOCK_BROKEN, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, otherTitle, otherMessage);

        if (setting("notification.email.lock-broken.enabled", false)) {
            emailBroadcastSplitByActor(resolveEnvironmentRecipients(environmentId), NotificationType.LOCK_BROKEN, adminUserId,
                    actorTitle, actorMessage, otherTitle, otherMessage);
        }
    }

    public void notifyAccessGranted(String userId, String environmentName, String environmentId) {
        String title = "Access granted: " + environmentName;
        String message = "You have been granted access to environment \"" + environmentName + "\".";
        create(userId, NotificationType.ACCESS_GRANTED, title, message, "ENVIRONMENT", environmentId);
        if (setting("notification.email.access-granted.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.ACCESS_GRANTED, title, message);
        }
    }

    public void notifyAccessLevelChanged(String userId, String environmentName, String environmentId,
                                         AccessLevel previousLevel, AccessLevel newLevel) {
        String title = "Access level changed: " + environmentName;
        String message = "Your access to environment \"" + environmentName + "\" changed from "
                + previousLevel + " to " + newLevel + ".";
        create(userId, NotificationType.ACCESS_LEVEL_CHANGED, title, message, "ENVIRONMENT", environmentId);
        if (setting("notification.email.access-granted.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.ACCESS_LEVEL_CHANGED, title, message);
        }
    }

    public void notifyAccessRevoked(String userId, String environmentName, String environmentId) {
        String title = "Access revoked: " + environmentName;
        String message = "Your access to environment \"" + environmentName + "\" has been revoked.";
        create(userId, NotificationType.ACCESS_REVOKED, title, message, "ENVIRONMENT", environmentId);
        if (setting("notification.email.access-revoked.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.ACCESS_REVOKED, title, message);
        }
    }

    public void notifyAccessRequestApproved(String userId, String environmentName, String environmentId) {
        String title = "Request approved: " + environmentName;
        String message = "Your access request for environment \"" + environmentName + "\" has been approved.";
        create(userId, NotificationType.ACCESS_REQUEST_APPROVED, title, message, "ENVIRONMENT", environmentId);
        if (setting("notification.email.access-request-approved.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.ACCESS_REQUEST_APPROVED, title, message);
        }
    }

    public void notifyAccessRequestDenied(String userId, String environmentName, String environmentId) {
        String title = "Request denied: " + environmentName;
        String message = "Your access request for environment \"" + environmentName + "\" has been denied.";
        create(userId, NotificationType.ACCESS_REQUEST_DENIED, title, message, "ENVIRONMENT", environmentId);
        if (setting("notification.email.access-request-denied.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.ACCESS_REQUEST_DENIED, title, message);
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
        if (chat != null) {
            try {
                // Approve opens a signed one-click confirm page; the message links to Pending Requests (E24-T04).
                chat.notifyWithActions(environmentId, NotificationType.ACCESS_REQUESTED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.INFO, title, message,
                        "#/pending-requests", List.of(new com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec(
                                com.tcgdigital.vmcontrol.model.ChatActionType.APPROVE_ACCESS_REQUEST, "Approve", requestId, "primary")),
                        null);
            } catch (RuntimeException e) {
                log.warn("Could not send the access request to chat: {}", e.getClass().getSimpleName());
            }
        }

        if (setting("notification.email.access-requested.enabled", false)) {
            // Email is intentionally narrower than the bell's reviewer set — All Admin only,
            // not env-admins (who are part of the bell's broadcast via resolveEnvironmentReviewers).
            sendEventEmail(userRepository.findByAdminTrueAndIsActiveTrue(), NotificationType.ACCESS_REQUESTED, title, message);
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

        if (created && setting("notification.email.access-expiring.enabled", false)) {
            List<User> recipients = new ArrayList<>();
            userRepository.findById(userId).ifPresent(recipients::add);
            recipients.addAll(resolveAdministeringEnvAdmins(environmentId));
            sendEventEmail(recipients, NotificationType.ACCESS_EXPIRING, title, message);
        }
    }

    // ============= Budgets (E20-T02) =============

    /** A budget's spend reached a threshold: the owners of its environments and the admins hear it. */
    public void notifyBudgetThreshold(List<String> environmentIds, String budgetId, boolean team, String scopeName,
                                      java.time.YearMonth month, int threshold, java.math.BigDecimal spend,
                                      java.math.BigDecimal amount) {
        String title = "Budget " + threshold + "% used: " + scopeName;
        String message = scopeName + " has spent " + dollars(spend) + " of its " + dollars(amount) + " budget for "
                + monthName(month) + ".";
        sendBudgetAlert(environmentIds, budgetId, team, NotificationType.BUDGET_THRESHOLD, title, message);
        budgetToChat(environmentIds, NotificationType.BUDGET_THRESHOLD, title, message,
                "BUDGET_THRESHOLD:" + budgetId + ":" + month + ":" + threshold);
    }

    /** A budget is forecast to overrun this month. */
    public void notifyBudgetForecast(List<String> environmentIds, String budgetId, boolean team, String scopeName,
                                     java.time.YearMonth month, java.math.BigDecimal forecast, java.math.BigDecimal amount) {
        String title = "Budget forecast overrun: " + scopeName;
        String message = "At the current rate " + scopeName + " will spend about " + dollars(forecast) + " in "
                + monthName(month) + ", over its " + dollars(amount) + " budget.";
        sendBudgetAlert(environmentIds, budgetId, team, NotificationType.BUDGET_FORECAST, title, message);
        budgetToChat(environmentIds, NotificationType.BUDGET_FORECAST, title, message, "BUDGET_FORECAST:" + budgetId + ":" + month);
    }

    /** Each covered environment's channels (a shared default channel hears it once); no environment: the defaults. */
    private void budgetToChat(List<String> environmentIds, NotificationType type, String title, String message, String dedupKey) {
        if (environmentIds.isEmpty()) {
            toChat(null, type, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message, dedupKey);
        } else {
            environmentIds.forEach(id -> toChat(id, type, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message, dedupKey));
        }
    }

    /**
     * Owners (environment ADMIN-grant holders and administering env admins) of every environment
     * the budget covers, plus the admins, each once. An environment budget's notification opens
     * the environment; a team budget's the budget.
     */
    private void sendBudgetAlert(List<String> environmentIds, String budgetId, boolean team, NotificationType type,
                                 String title, String message) {
        Map<String, User> recipients = new LinkedHashMap<>();
        userRepository.findByAdminTrueAndIsActiveTrue().forEach(u -> recipients.put(u.getUserId(), u));
        environmentIds.forEach(id -> resolveEnvironmentReviewers(id).forEach(u -> recipients.put(u.getUserId(), u)));
        String entityType = team || environmentIds.isEmpty() ? "BUDGET" : "ENVIRONMENT";
        String entityId = team || environmentIds.isEmpty() ? budgetId : environmentIds.get(0);
        recipients.values().forEach(u -> create(u.getUserId(), type, title, message, entityType, entityId));
        if (setting("notification.email.budget-alert.enabled", false)) {
            sendEventEmail(List.copyOf(recipients.values()), type, title, message);
        }
    }

    // ============= Cost anomalies (E21-T03) =============

    /**
     * Unexpected spend in an environment: its owners, the admins and administering env admins.
     * Without an environment (an unlinked monitor) only the admins hear it.
     */
    public void notifyCostAnomaly(String environmentId, String environmentName, java.math.BigDecimal impact,
                                  java.time.LocalDate since, String topCause, String anomalyId) {
        String where = environmentName == null ? "An unlinked AWS anomaly monitor" : environmentName;
        String title = "Cost anomaly: " + where;
        String message = where + ": unexpected spend of " + dollars(impact)
                + (since == null ? "" : " since " + since)
                + (topCause == null ? "" : " (top cause: " + topCause + ")") + ".";
        List<User> recipients = environmentId == null ? userRepository.findByAdminTrueAndIsActiveTrue()
                : resolveEnvironmentReviewers(environmentId);
        recipients.forEach(u -> create(u.getUserId(), NotificationType.COST_ANOMALY, title, message, "COST_ANOMALY", anomalyId));
        if (chat != null) {
            try {
                // Opens the environment; 'Create ticket' (Jira on, environment known) is a one-click link (E24-T07).
                chat.notifyWithActions(environmentId, NotificationType.COST_ANOMALY,
                        com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message,
                        environmentId == null ? "#/cost-management" : null,
                        jiraEnabled && environmentId != null
                                ? List.of(new com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec(
                                        com.tcgdigital.vmcontrol.model.ChatActionType.CREATE_JIRA_TICKET, "Create ticket", anomalyId, null))
                                : List.of(),
                        "COST_ANOMALY:" + anomalyId);
            } catch (RuntimeException e) {
                log.warn("Could not send the cost anomaly to chat: {}", e.getClass().getSimpleName());
            }
        }
        if (setting("notification.email.cost-anomaly.enabled", false)) {
            try {
                sendEventEmail(recipients, NotificationType.COST_ANOMALY, title, message);
            } catch (Exception e) {
                log.warn("Could not email cost anomaly {}: {}", anomalyId, e.getMessage());
            }
        }
    }

    /** The admin who asked for a safe resize hears how it ended (E30-T06). */
    public void notifyResizeOutcome(String userId, String vmId, boolean succeeded, String title, String message) {
        if (userId == null) {
            return;
        }
        create(userId, succeeded ? NotificationType.OPERATION_COMPLETED : NotificationType.OPERATION_FAILED,
                title, message, "VM", vmId);
    }

    /** The admin who converted a node group to Spot (or back) hears how it ended (E31-T05). */
    public void notifySpotOutcome(String userId, String vmId, boolean succeeded, String title, String message) {
        if (userId == null) {
            return;
        }
        create(userId, succeeded ? NotificationType.OPERATION_COMPLETED : NotificationType.OPERATION_FAILED,
                title, message, "VM", vmId);
    }

    /** Approvers hear about a request for an environment from a template (E32-T03). */
    public void notifyProvisioningRequested(List<String> approverIds, String requestId, String environmentName,
                                            String templateName, String requesterName) {
        String title = "Environment requested: " + environmentName;
        String message = (requesterName == null ? "Someone" : requesterName) + " asks for a new environment '" + environmentName
                + "' from the template " + templateName + ". Open Provisioning requests to approve or reject it.";
        approverIds.forEach(id -> create(id, NotificationType.PROVISIONING_REQUESTED, title, message, "PROVISIONING_REQUEST", requestId));
    }

    /** The requester hears the decision on their environment request (E32-T03). */
    public void notifyProvisioningDecision(String requesterId, String requestId, String environmentName, boolean approved, String notes) {
        String title = (approved ? "Environment request approved: " : "Environment request rejected: ") + environmentName;
        String message = approved ? "Your request for '" + environmentName + "' was approved; it will be built shortly."
                : "Your request for '" + environmentName + "' was rejected." + (notes == null || notes.isBlank() ? "" : " Reason: " + notes);
        create(requesterId, approved ? NotificationType.PROVISIONING_APPROVED : NotificationType.PROVISIONING_REJECTED,
                title, message, "PROVISIONING_REQUEST", requestId);
    }

    /** The requester hears whether their environment was built (E32-T04). */
    public void notifyProvisioningOutcome(String requesterId, String requestId, String environmentName, String environmentId,
                                          boolean ready, String detail) {
        String title = (ready ? "Environment ready: " : "Environment not built: ") + environmentName;
        String message = ready ? "'" + environmentName + "' is ready and you are its admin. " + (detail == null ? "" : detail)
                : "'" + environmentName + "' could not be built. " + (detail == null ? "" : detail);
        create(requesterId, ready ? NotificationType.PROVISIONING_READY : NotificationType.PROVISIONING_FAILED, title, message,
                ready ? "ENVIRONMENT" : "PROVISIONING_REQUEST", ready ? environmentId : requestId);
    }

    /** The requester of a provisioned environment hears it will be destroyed soon (E32-T05). */
    public void notifyEnvironmentDestroyWarning(String requesterId, String environmentId, String environmentName,
                                                java.time.Instant destroyAt, String within) {
        create(requesterId, NotificationType.ENVIRONMENT_DESTROY_WARNING, "Destroyed within " + within + ": " + environmentName,
                "'" + environmentName + "' and everything in it will be destroyed at " + destroyAt
                        + ". Extend it from My requests if you still need it.", "ENVIRONMENT", environmentId);
    }

    /** The requester hears their provisioned environment was destroyed, or that destroying it failed (E32-T05). */
    public void notifyEnvironmentDestroyed(String requesterId, String environmentId, String environmentName, boolean destroyed,
                                           String error) {
        create(requesterId, destroyed ? NotificationType.ENVIRONMENT_DESTROYED : NotificationType.OPERATION_FAILED,
                (destroyed ? "Environment destroyed: " : "Destroy failed: ") + environmentName,
                destroyed ? "'" + environmentName + "' was destroyed and removed from the platform."
                        : "Destroying '" + environmentName + "' failed: " + error + ". An admin needs to look at it.",
                "ENVIRONMENT", environmentId);
        if (!destroyed) {
            userRepository.findByAdminTrueAndIsActiveTrue().forEach(u -> create(u.getUserId(), NotificationType.OPERATION_FAILED,
                    "Destroy failed: " + environmentName, "Destroying '" + environmentName + "' failed: " + error + ".",
                    "ENVIRONMENT", environmentId));
        }
    }

    /** AWS took Spot capacity back: the environment's admins and the lock holder hear it (E31-T07). */
    public void notifySpotInterrupted(String environmentId, String environmentName, String vmId, String vmName,
                                      String lockHolderUserId, String details) {
        String title = "Spot interruption: " + vmName;
        String message = "AWS took back the Spot capacity of " + vmName + " in " + environmentName + ". " + details;
        java.util.Set<String> recipients = new java.util.LinkedHashSet<>();
        resolveEnvironmentReviewers(environmentId).forEach(u -> recipients.add(u.getUserId()));
        if (lockHolderUserId != null) {
            recipients.add(lockHolderUserId);
        }
        recipients.forEach(id -> create(id, NotificationType.SPOT_INTERRUPTED, title, message, "VM", vmId));
        toChat(environmentId, NotificationType.SPOT_INTERRUPTED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING,
                title, message);
    }

    /** A Spot twin lost capacity and its standby started (or it recovered): the environment's admins hear it (E31-T06). */
    public void notifySpotFallback(String environmentId, String vmId, boolean activated, String title, String message) {
        resolveEnvironmentReviewers(environmentId).forEach(u -> create(u.getUserId(),
                activated ? NotificationType.OPERATION_FAILED : NotificationType.OPERATION_COMPLETED, title, message, "VM", vmId));
        toChat(environmentId, activated ? NotificationType.OPERATION_FAILED : NotificationType.OPERATION_COMPLETED,
                activated ? com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING
                        : com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.INFO, title, message);
    }

    /** AWS started a database the platform keeps stopped (E33-T04): the environment's admins hear whether it is stopped again. */
    public void notifyRdsAutoStart(String environmentId, String environmentName, String vmId, String vmName, boolean restopping) {
        String title = "AWS restarted " + vmName;
        String message = "AWS started " + vmName + " in " + environmentName + " after seven days stopped. "
                + (restopping ? "The platform will stop it again once it is available."
                : "Keep-stopped is off, so it keeps running (and costing) until someone stops it.");
        resolveEnvironmentReviewers(environmentId).forEach(u -> create(u.getUserId(), NotificationType.STATE_DRIFT_DETECTED,
                title, message, "VM", vmId));
        toChat(environmentId, NotificationType.STATE_DRIFT_DETECTED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING,
                title, message);
    }

    /** A safe resize failed and its rollback too: the environment's admins must fix the VM (E30-T06). */
    public void notifyResizeNeedsAttention(String environmentId, String vmId, String title, String message) {
        resolveEnvironmentReviewers(environmentId).forEach(u -> create(u.getUserId(), NotificationType.OPERATION_FAILED,
                title, message, "VM", vmId));
        toChat(environmentId, NotificationType.OPERATION_FAILED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.CRITICAL,
                title, message);
    }

    /** An exhausted budget's action needs an owner's decision (E20-T03). */
    public void notifyBudgetActionPending(String environmentId, String environmentName, String requestId,
                                          com.tcgdigital.vmcontrol.model.BudgetActionType action, java.time.YearMonth month) {
        String what = action == com.tcgdigital.vmcontrol.model.BudgetActionType.STOP_ENVIRONMENT
                ? "stop the environment" : "freeze starts";
        String title = "Approve budget action: " + environmentName;
        String message = environmentName + " has used its whole budget for " + monthName(month) + ". Its budget asks to "
                + what + "; nothing happens until an owner approves it.";
        resolveEnvironmentReviewers(environmentId).forEach(u -> create(u.getUserId(),
                NotificationType.BUDGET_ACTION_PENDING, title, message, "BUDGET_ACTION", requestId));
        toChat(environmentId, NotificationType.BUDGET_ACTION_PENDING, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message);
    }

    /** The approver of a budget stop hears why it has not run yet (E20-T04). */
    public void notifyBudgetStopDeferred(String approverUserId, String environmentId, String environmentName, String why) {
        create(approverUserId, NotificationType.BUDGET_ACTION_PENDING, "Budget stop waiting: " + environmentName,
                "The approved budget stop of " + environmentName + " has not run yet: " + why
                        + ". It is retried at the next daily budget evaluation.", "ENVIRONMENT", environmentId);
    }

    /** Everyone with access hears that starts are frozen (E20-T03). */
    public void notifyBudgetFreeze(String environmentId, String environmentName, java.time.Instant until) {
        String title = "Starts frozen: " + environmentName;
        String message = "Starting VMs in " + environmentName + " is blocked until "
                + until.atZone(java.time.ZoneOffset.UTC).toLocalDate() + " because its budget is exhausted. "
                + "Stops still work; an owner can lift the freeze.";
        resolveEnvironmentRecipients(environmentId).forEach(u -> create(u.getUserId(),
                NotificationType.BUDGET_FREEZE, title, message, "ENVIRONMENT", environmentId));
        toChat(environmentId, NotificationType.BUDGET_FREEZE, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message);
    }

    private static String dollars(java.math.BigDecimal value) {
        return value == null ? "$0.00" : "$" + String.format(java.util.Locale.US, "%,.2f", value);
    }

    private static String monthName(java.time.YearMonth month) {
        return month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + month.getYear();
    }

    // ============= Approvals (E25-T04) =============

    /** The approvers hear that a start or extension waits for them; the environment's channels get an Approve button. */
    public void notifyApprovalRequested(List<String> approverIds, String environmentId, String environmentName, String requestId,
                                        String requesterName, String type, String scope, java.math.BigDecimal estimatedCost,
                                        List<String> reasons) {
        String what = "EXTEND".equals(type) ? "keep " + environmentName + " running longer" : "start " + environmentName;
        String title = "Approval needed: " + environmentName;
        String message = requesterName + " asked to " + what + " (" + scope
                + (estimatedCost == null ? "" : ", about " + dollars(estimatedCost)) + "). "
                + (reasons.isEmpty() ? "" : "Why approval is needed: " + String.join("; ", reasons) + ".");
        approverIds.forEach(id -> create(id, NotificationType.APPROVAL_REQUESTED, title, message, "APPROVAL_REQUEST", requestId));
        if (chat != null) {
            try {
                chat.notifyWithActions(environmentId, NotificationType.APPROVAL_REQUESTED,
                        com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.INFO, title, message, "#/approvals",
                        List.of(new com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec(
                                com.tcgdigital.vmcontrol.model.ChatActionType.APPROVE_OPERATION, "Approve", requestId, "primary")),
                        "APPROVAL_REQUESTED:" + requestId);
            } catch (RuntimeException e) {
                log.warn("Could not send the approval request to chat: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** A team's quota reached its warning level or ran out this month (E25-T07): its members hear it once. */
    public void notifyQuotaThreshold(List<String> memberIds, String teamId, String title, String message) {
        memberIds.forEach(id -> create(id, NotificationType.QUOTA_THRESHOLD, title, message, "TEAM", teamId));
    }

    /** The requester hears how their request ended (approved and running, denied, expired, failed). */
    public void notifyApprovalOutcome(String requesterId, String environmentId, String environmentName, String requestId,
                                      NotificationType type, String title, String message) {
        create(requesterId, type, title, message, "APPROVAL_REQUEST", requestId);
    }

    // ============= Environment leases (E15-T04) =============

    /**
     * Warn a lease participant that the environment stops soon. One per user and warning window
     * ({@code dedupSince} = end - threshold), so an extension re-arms the warnings. The bell
     * opens the environment page, where Extend is offered.
     */
    public boolean notifyLeaseExpiring(String userId, String environmentId, String environmentName,
                                       Timestamp endsAt, long minutesLeft, Timestamp dedupSince) {
        String title = "Environment stopping soon: " + environmentName;
        String message = "\"" + environmentName + "\" stops at " + formatTime(endsAt) + " (in " + minutesLeft
                + " min). Extend it from the environment page.";
        boolean created = createIfAbsentSince(userId, NotificationType.LEASE_EXPIRING, title, message,
                "ENVIRONMENT", environmentId, dedupSince);
        if (created && chat != null) {
            try {
                // One message per warning window (60/15/5 min) however many participants there are;
                // 'Extend 2h' is a one-click link valid until the lease ends (at least 5 minutes) (E24-T05).
                java.time.Duration ttl = endsAt == null ? java.time.Duration.ofMinutes(5)
                        : java.time.Duration.between(java.time.Instant.now(), endsAt.toInstant());
                if (ttl.compareTo(java.time.Duration.ofMinutes(5)) < 0) {
                    ttl = java.time.Duration.ofMinutes(5);
                }
                chat.notifyWithActions(environmentId, NotificationType.LEASE_EXPIRING,
                        com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title,
                        "\"" + environmentName + "\" stops at " + formatTime(endsAt) + " (in " + minutesLeft
                                + " min) unless someone extends it.",
                        null,
                        List.of(new com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec(
                                com.tcgdigital.vmcontrol.model.ChatActionType.EXTEND_LEASE, "Extend 2h", null, "primary", ttl)),
                        "LEASE_EXPIRING:" + environmentId + ":" + (endsAt == null ? "" : endsAt.getTime())
                                + ":" + (dedupSince == null ? "" : dedupSince.getTime()));
            } catch (RuntimeException e) {
                log.warn("Could not send the lease warning to chat: {}", e.getClass().getSimpleName());
            }
        }
        if (created && setting("notification.email.lease-expiring.enabled", false)) {
            sendEventEmailToUser(userId, NotificationType.LEASE_EXPIRING, title, message);
        }
        return created;
    }

    /** A lease ended and the platform is stopping the environment: participants and owners hear it. */
    public void notifyLeaseExpiredForEnvironment(String environmentId, String environmentName,
                                                 java.util.Collection<String> participantIds, String detail) {
        String title = "Lease expired: " + environmentName;
        String message = "\"" + environmentName + "\" " + detail;
        Map<String, User> recipients = new LinkedHashMap<>();
        userRepository.findAllById(participantIds).forEach(u -> recipients.put(u.getUserId(), u));
        resolveEnvironmentReviewers(environmentId).forEach(u -> recipients.put(u.getUserId(), u));
        recipients.values().stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .forEach(u -> create(u.getUserId(), NotificationType.LEASE_EXPIRED, title, message,
                        "ENVIRONMENT", environmentId));
        toChat(environmentId, NotificationType.LEASE_EXPIRED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING, title, message);
    }

    /** Tell the environment's owners that someone asked to run past the maximum. */
    public void notifyLeaseExtensionRequested(String environmentId, String environmentName, String requesterUserId,
                                              int minutes, String reason) {
        String title = "Lease extension requested: " + environmentName;
        String message = resolveUserDisplayName(requesterUserId) + " asked to keep \"" + environmentName
                + "\" running " + minutes + " more minutes, beyond its maximum"
                + (isBlank(reason) ? "." : ": " + reason);
        resolveEnvironmentReviewers(environmentId).forEach(user -> create(user.getUserId(),
                NotificationType.LEASE_EXTENSION_REQUESTED, title, message, "ENVIRONMENT", environmentId));
        toChat(environmentId, NotificationType.LEASE_EXTENSION_REQUESTED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.INFO, title, message);
    }

    /** Tell the requester what the owner decided. */
    public void notifyLeaseExtensionDecided(String requesterUserId, String environmentId, String environmentName,
                                            boolean approved, int minutes, String notes) {
        String title = (approved ? "Extension approved: " : "Extension denied: ") + environmentName;
        String message = "Your request to keep \"" + environmentName + "\" running " + minutes + " more minutes was "
                + (approved ? "approved" : "denied") + (isBlank(notes) ? "." : ": " + notes);
        create(requesterUserId, NotificationType.LEASE_EXTENSION_DECIDED, title, message, "ENVIRONMENT", environmentId);
    }

    private static String formatTime(Timestamp ts) {
        return ts == null ? "-" : java.time.format.DateTimeFormatter.ofPattern("HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(ts.toInstant());
    }

    public void notifyAccessExpired(String userId, String scopeLabel, String environmentId,
                                    String accessId) {
        String title = "Access expired: " + scopeLabel;
        String message = "Your access to \"" + scopeLabel + "\" has expired.";
        boolean created = createIfAbsent(userId, NotificationType.ACCESS_EXPIRED, title, message, "ACCESS", accessId);

        if (created && setting("notification.email.access-expired.enabled", false)) {
            List<User> recipients = new ArrayList<>();
            userRepository.findById(userId).ifPresent(recipients::add);
            recipients.addAll(resolveAdministeringEnvAdmins(environmentId));
            sendEventEmail(recipients, NotificationType.ACCESS_EXPIRED, title, message);
        }
    }

    public void notifyOperationCompleted(String userId, String environmentId, String environmentName, String operationType) {
        create(userId, NotificationType.OPERATION_COMPLETED,
               operationType + " completed: " + environmentName,
               "The " + operationType.toLowerCase() + " operation on environment \"" +
                       environmentName + "\" finished successfully.",
               "ENVIRONMENT", environmentId);
    }

    public void notifyOperationFailed(String userId, String environmentId, String environmentName, String operationType,
                                      String reason) {
        create(userId, NotificationType.OPERATION_FAILED,
               operationType + " failed: " + environmentName,
               "The " + operationType.toLowerCase() + " operation on environment \"" +
                       environmentName + "\" failed. Reason: " + reason,
               "ENVIRONMENT", environmentId);
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
        toChat(environmentId, NotificationType.OPERATION_FAILED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.CRITICAL, otherTitle, otherMessage);

        if (setting("notification.email.operation-failed.enabled", false)) {
            // Env admin explicitly excluded from this email per spec, unlike the bell broadcast.
            emailBroadcastSplitByActor(resolveEnvironmentRecipients(environmentId, false), NotificationType.OPERATION_FAILED, actorUserId,
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
        toChat(environmentId, NotificationType.IDLE_AUTO_STOPPED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.INFO, title,
                "Stopped " + vmCount + " VM(s) after " + idleMinutes + " minutes idle. It can be started again from the environment page.");
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
        toChat(environmentId, NotificationType.STATE_DRIFT_DETECTED, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING,
                "State drift detected: " + environmentName,
                "State drift detected for \"" + vmName + "\": " + previousStatus + " -> " + currentStatus + ".");
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
        // One saveAll for the whole broadcast (E11-T08); preferences in one query too (E29-T05).
        List<User> recipients = resolveEnvironmentRecipients(environmentId);
        java.util.Set<String> wanted = preferences == null
                ? recipients.stream().map(User::getUserId).collect(java.util.stream.Collectors.toSet())
                : preferences.inAppRecipients(type, recipients.stream().map(User::getUserId).toList());
        List<Notification> batch = recipients.stream()
                .filter(user -> wanted.contains(user.getUserId()))
                .map(user -> {
                    boolean isActor = user.getUserId().equals(actorUserId);
                    return newNotification(user.getUserId(), type,
                            isActor ? actorTitle : otherTitle,
                            isActor ? actorMessage : otherMessage,
                            entityType, entityId);
                })
                .toList();
        notificationRepository.saveAll(batch);
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
        // One query, not one per env-admin (E11-T08).
        return accessRepository.findActiveEnvAdminGrantHolders(environmentId, new Timestamp(System.currentTimeMillis()));
    }

    private String resolveUserDisplayName(String userId) {
        if (isBlank(userId)) {
            return "Automation"; // a system-initiated action has no actor (E11-T08)
        }
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
        if (preferences != null && !preferences.wantsInApp(userId, type)) {
            log.debug("User {} turned off in-app {}", userId, type); // E29-T05
            return;
        }
        notificationRepository.save(newNotification(userId, type, title, message, entityType, entityId));
        log.debug("Notification created for user {}: {}", userId, title);
    }

    private static Notification newNotification(String userId, NotificationType type, String title, String message,
                                                String entityType, String entityId) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID().toString());
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setEntityType(entityType);
        n.setEntityId(entityId);
        return n;
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

    private void sendEventEmailToUser(String userId, NotificationType type, String subject, String bodyText) {
        userRepository.findById(userId).ifPresent(user -> sendEventEmail(List.of(user), type, subject, bodyText));
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

        notificationRepository.saveAll(recipients.stream()
                .map(user -> {
                    boolean isActor = user.getUserId().equals(actorUserId);
                    return newNotification(user.getUserId(), NotificationType.ENVIRONMENT_STOP_NOTICE,
                            isActor ? actorTitle : otherTitle, isActor ? actorMessage : otherMessage,
                            "ENVIRONMENT", environmentId);
                })
                .toList());
        if (chat != null) {
            try {
                // "Stop now" only with chat.actions.stop-enabled (off until the flow is security-reviewed).
                chat.notifyWithActions(environmentId, NotificationType.ENVIRONMENT_STOP_NOTICE, com.tcgdigital.vmcontrol.service.chat.ChatMessage.Severity.WARNING,
                        otherTitle, otherMessage, null,
                        chatStopActionsEnabled ? List.of(new com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec(
                                com.tcgdigital.vmcontrol.model.ChatActionType.STOP_ENVIRONMENT, "Stop now", null, "danger")) : List.of(),
                        null);
            } catch (RuntimeException e) {
                log.warn("Could not send the stop notice to chat: {}", e.getClass().getSimpleName());
            }
        }

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

    private void sendEventEmail(List<User> recipients, NotificationType type, String subject, String bodyText) {
        // Users who turned this email off are dropped, with one preference query (E29-T05).
        List<User> wanted = preferences == null ? recipients : preferences.emailRecipients(type, recipients);
        List<String> addresses = wanted.stream()
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
    private void emailBroadcastSplitByActor(List<User> recipients, NotificationType type, String actorUserId,
                                            String actorTitle, String actorMessage,
                                            String otherTitle, String otherMessage) {
        List<User> actor = recipients.stream().filter(u -> u.getUserId().equals(actorUserId)).toList();
        List<User> others = recipients.stream().filter(u -> !u.getUserId().equals(actorUserId)).toList();
        if (!actor.isEmpty()) {
            sendEventEmail(actor, type, actorTitle, actorMessage);
        }
        if (!others.isEmpty()) {
            sendEventEmail(others, type, otherTitle, otherMessage);
        }
    }
}
