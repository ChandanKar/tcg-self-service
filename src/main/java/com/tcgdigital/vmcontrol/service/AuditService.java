package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for audit logging.
 * All audit log operations are async and non-blocking.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository,
                        EnvironmentRepository environmentRepository,
                        UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
    }

    // ============= Logging Methods =============

    /**
     * Log an audit action asynchronously.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String userId, AuditAction action, String targetType, String targetId,
                          String targetName, String details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(userId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetName(targetName)
                    .details(details)
                    .success(true)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit log created: {} - {} on {}", action, userId, targetName);

        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Log an audit action with environment context.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEnvironmentAction(String userId, AuditAction action, String environmentId,
                                     String environmentName, String targetType, String targetId,
                                     String targetName, String details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(userId)
                    .action(action)
                    .environmentId(environmentId)
                    .environmentName(environmentName)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetName(targetName)
                    .details(details)
                    .success(true)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit log created: {} - {} on {} in {}", action, userId, targetName, environmentName);

        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Log an audit action with change tracking.
     * Note: old/new values are embedded in details since V1 schema doesn't have separate columns.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logChange(String userId, AuditAction action, String targetType, String targetId,
                          String targetName, String oldValue, String newValue, String details) {
        try {
            // Embed old/new values in details
            String changeDetails = String.format("%s | Changed from: %s to: %s",
                    details != null ? details : "", oldValue, newValue);

            AuditLog auditLog = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(userId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetName(targetName)
                    .details(changeDetails)
                    .success(true)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit log created: {} - {} changed {}", action, userId, targetName);

        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Log a failed action.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(String userId, AuditAction action, String targetType, String targetId,
                           String targetName, String errorMessage) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(userId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetName(targetName)
                    .success(false)
                    .errorMessage(errorMessage)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit failure logged: {} - {} on {}: {}", action, userId, targetName, errorMessage);

        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Log a failed action with environment context.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEnvironmentFailure(String userId, AuditAction action, String environmentId,
                                      String environmentName, String targetType, String targetId,
                                      String targetName, String errorMessage) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(userId)
                    .action(action)
                    .environmentId(environmentId)
                    .environmentName(environmentName)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetName(targetName)
                    .success(false)
                    .errorMessage(errorMessage)
                    .details(errorMessage)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit failure logged: {} - {} on {} in {}: {}",
                    action, userId, targetName, environmentName, errorMessage);

        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Log an action with full context (synchronous for critical operations).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog logActionSync(AuditLog.Builder builder) {
        try {
            if (builder.build().getLogId() == null) {
                builder.logId(UUID.randomUUID().toString());
            }
            AuditLog auditLog = builder.build();
            return auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to create sync audit log: {}", e.getMessage());
            return null;
        }
    }

    // ============= Query Methods =============

    /**
     * Get recent audit logs.
     */
    public List<AuditLog> getRecentLogs() {
        return enrichEnvironmentInfo(auditLogRepository.findTop100ByOrderByCreatedAtDesc());
    }

    /**
     * Get audit logs for an environment.
     */
    public Page<AuditLog> getLogsForEnvironment(String environmentId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findByEnvironmentIdOrderByCreatedAtDesc(environmentId, pageable));
    }

    /**
     * Get audit logs for a user.
     */
    public Page<AuditLog> getLogsForUser(String userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable));
    }

    /**
     * Get audit logs for a user, optionally filtered by date range.
     */
    public Page<AuditLog> getLogsForUserInRange(String userId, LocalDate startDate, LocalDate endDate, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Timestamp start = startDate != null ? Timestamp.valueOf(startDate.atStartOfDay()) : null;
        Timestamp end = endDate != null ? Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()) : null;
        return enrichEnvironmentInfo(auditLogRepository.findUserActivity(userId, start, end, null, null, pageable));
    }

    /**
     * Get audit logs for a user with optional activity-log filters.
     */
    public Page<AuditLog> getUserActivityLogs(String userId, LocalDate startDate, LocalDate endDate,
                                              String environmentId, AuditAction action, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Timestamp start = startDate != null ? Timestamp.valueOf(startDate.atStartOfDay()) : null;
        Timestamp end = endDate != null ? Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()) : null;
        return enrichEnvironmentInfo(auditLogRepository.findUserActivity(
                userId,
                start,
                end,
                blankToNull(environmentId),
                action != null ? action.name() : null,
                pageable));
    }

    /**
     * Get audit logs for a specific target.
     */
    public Page<AuditLog> getLogsForTarget(String targetType, String targetId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable));
    }

    /**
     * Get audit logs by action type.
     */
    public Page<AuditLog> getLogsByAction(AuditAction action, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findByActionOrderByCreatedAtDesc(action.name(), pageable));
    }

    /**
     * Get audit logs within date range.
     */
    public Page<AuditLog> getLogsInDateRange(LocalDate startDate, LocalDate endDate, int page, int size) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable));
    }

    /**
     * Get failed operations.
     */
    public Page<AuditLog> getFailedOperations(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.findFailedOperations(pageable));
    }

    /**
     * Search audit logs.
     */
    public Page<AuditLog> searchLogs(String searchText, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return enrichEnvironmentInfo(auditLogRepository.searchByDetails(searchText, pageable));
    }

    // ============= Reporting Methods =============

    /**
     * Get action counts by type for a date range.
     */
    public Map<AuditAction, Long> getActionCountsByType(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());

        List<Object[]> results = auditLogRepository.countActionsByTypeInRange(start, end);

        Map<AuditAction, Long> resultMap = new HashMap<>();
        for (Object[] row : results) {
            String actionStr = (String) row[0];
            Long count = (Long) row[1];
            try {
                AuditAction action = AuditAction.valueOf(actionStr);
                resultMap.put(action, count);
            } catch (IllegalArgumentException e) {
                // Skip unknown actions
            }
        }
        return resultMap;
    }

    /**
     * Get action counts by user for a date range.
     */
    public Map<String, Long> getActionCountsByUser(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());

        List<Object[]> results = auditLogRepository.countActionsByUserInRange(start, end);

        return results.stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }

    /**
     * Get action counts by environment for a date range.
     */
    public List<EnvironmentActivitySummary> getActionCountsByEnvironment(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());

        List<Object[]> results = auditLogRepository.countActionsByEnvironmentInRange(start, end);

        return results.stream()
                .map(row -> new EnvironmentActivitySummary(
                        (String) row[0],
                        (String) row[1],
                        (Long) row[2]
                ))
                .toList();
    }

    /**
     * Get lock operations for compliance.
     */
    public List<AuditLog> getLockOperationsReport(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return enrichEnvironmentInfo(auditLogRepository.findLockOperationsInRange(start, end));
    }

    /**
     * Get VM operations for compliance.
     */
    public List<AuditLog> getVmOperationsReport(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return enrichEnvironmentInfo(auditLogRepository.findVmOperationsInRange(start, end));
    }

    // ============= Convenience Methods for Common Actions =============

    private String auditUserId(String userId) {
        if (userId == null || userId.isBlank() || "system".equalsIgnoreCase(userId)) {
            return null;
        }
        return userId;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Page<AuditLog> enrichEnvironmentInfo(Page<AuditLog> logs) {
        enrichEnvironmentInfo(logs.getContent());
        return logs;
    }

    private List<AuditLog> enrichEnvironmentInfo(List<AuditLog> logs) {
        Map<String, String> namesById = environmentRepository.findAllById(
                logs.stream()
                        .map(this::resolveEnvironmentId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.toMap(
                Environment::getEnvironmentId,
                env -> firstNonBlank(env.getDisplayName(), env.getName(), env.getEnvironmentId())
        ));

        logs.forEach(logEntry -> {
            String environmentId = resolveEnvironmentId(logEntry);
            if (environmentId != null && !environmentId.isBlank()) {
                logEntry.setEnvironmentId(environmentId);
                logEntry.setEnvironmentName(firstNonBlank(
                        logEntry.getEnvironmentName(),
                        namesById.get(environmentId),
                        environmentTargetName(logEntry)
                ));
            }
        });
        enrichUserInfo(logs);
        return logs;
    }

    private void enrichUserInfo(List<AuditLog> logs) {
        Map<String, User> usersById = userRepository.findAllById(
                logs.stream()
                        .map(AuditLog::getUserId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.toMap(User::getUserId, user -> user));

        logs.forEach(logEntry -> {
            User user = usersById.get(logEntry.getUserId());
            if (user != null) {
                logEntry.setUserDisplayName(firstNonBlank(user.getDisplayName(), user.getEmail()));
                logEntry.setUserEmail(user.getEmail());
            }
        });
    }

    private String resolveEnvironmentId(AuditLog logEntry) {
        if (logEntry.getEnvironmentId() != null && !logEntry.getEnvironmentId().isBlank()) {
            return logEntry.getEnvironmentId();
        }
        String targetType = logEntry.getTargetType();
        if (targetType != null) {
            String normalizedType = targetType.toLowerCase();
            if (normalizedType.equals("environment")
                    || normalizedType.equals("environment_access")
                    || normalizedType.equals("lock")) {
                return logEntry.getTargetId();
            }
        }
        return null;
    }

    private String environmentTargetName(AuditLog logEntry) {
        String targetType = logEntry.getTargetType();
        if (targetType != null && targetType.toLowerCase().startsWith("environment")) {
            return logEntry.getTargetName();
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public void logEnvironmentCreated(String userId, String environmentId, String environmentName) {
        logEnvironmentAction(auditUserId(userId), AuditAction.ENVIRONMENT_CREATED, environmentId, environmentName,
                "environment", environmentId, environmentName,
                "Environment created: " + environmentName + " by user: " + userId);
    }

    public void logGroupCreated(String userId, String environmentId, String environmentName,
                                String groupId, String groupName) {
        logEnvironmentAction(auditUserId(userId), AuditAction.GROUP_CREATED, environmentId, environmentName,
                "group", groupId, groupName,
                "Group created: " + groupName + " in environment: " + environmentName + " by user: " + userId);
    }

    public void logVmRegistered(String userId, String environmentId, String environmentName,
                                String vmId, String vmName) {
        logEnvironmentAction(auditUserId(userId), AuditAction.VM_REGISTERED, environmentId, environmentName,
                "vm", vmId, vmName,
                "VM registered: " + vmName + " in environment: " + environmentName + " by user: " + userId);
    }

    public void logLockAcquired(String userId, String environmentId, String environmentName, String reason) {
        logEnvironmentAction(auditUserId(userId), AuditAction.LOCK_ACQUIRED, environmentId, environmentName,
                "lock", environmentId, environmentName,
                "Lock acquired by user: " + userId + ". Environment: " + environmentName + ". Reason: " + reason);
    }

    public void logLockReleased(String userId, String environmentId, String environmentName) {
        logEnvironmentAction(auditUserId(userId), AuditAction.LOCK_RELEASED, environmentId, environmentName,
                "lock", environmentId, environmentName,
                "Lock released by user: " + userId + ". Environment: " + environmentName);
    }

    public void logLockBroken(String adminId, String environmentId, String environmentName,
                              String originalHolder, String reason) {
        logEnvironmentAction(auditUserId(adminId), AuditAction.LOCK_BROKEN, environmentId, environmentName,
                "lock", environmentId, environmentName,
                "Lock broken by admin: " + adminId + ". Original holder: " + originalHolder + ". Reason: " + reason);
    }

    public void logOperationStarted(String userId, String environmentId, String environmentName,
                                    String executionId, String operationType) {
        logEnvironmentAction(auditUserId(userId), AuditAction.OPERATION_STARTED, environmentId, environmentName,
                "operation", executionId, operationType,
                "Operation started by user: " + userId + ". Environment: " + environmentName);
    }

    public void logOperationCompleted(String userId, String environmentId, String environmentName,
                                      String executionId, String operationType, int totalVms, int failed) {
        String details = String.format("Operation completed by user: %s. Environment: %s. Total VMs: %d, Failed: %d",
                userId, environmentName, totalVms, failed);
        logEnvironmentAction(auditUserId(userId), AuditAction.OPERATION_COMPLETED, environmentId, environmentName,
                "operation", executionId, operationType, details);
    }

    public void logOperationFailed(String userId, String environmentId, String environmentName,
                                   String executionId, String operationType, String errorMessage) {
        logEnvironmentFailure(auditUserId(userId), AuditAction.OPERATION_FAILED, environmentId, environmentName,
                "operation", executionId, operationType,
                "User: " + userId + ". Environment: " + environmentName + ". Error: " + errorMessage);
    }

    public void logVmStarted(String userId, String vmId, String vmName, String environmentId) {
        logEnvironmentAction(auditUserId(userId), AuditAction.VM_START_COMPLETED, environmentId, null,
                "vm", vmId, vmName,
                "VM started successfully by user: " + userId);
    }

    public void logVmStopped(String userId, String vmId, String vmName, String environmentId) {
        logEnvironmentAction(auditUserId(userId), AuditAction.VM_STOP_COMPLETED, environmentId, null,
                "vm", vmId, vmName,
                "VM stopped successfully by user: " + userId);
    }

    public void logVmStartFailed(String userId, String vmId, String vmName, String environmentId, String error) {
        logEnvironmentFailure(auditUserId(userId), AuditAction.VM_START_FAILED, environmentId, null,
                "vm", vmId, vmName,
                "User: " + userId + ". Error: " + error);
    }

    public void logVmStopFailed(String userId, String vmId, String vmName, String environmentId, String error) {
        logEnvironmentFailure(auditUserId(userId), AuditAction.VM_STOP_FAILED, environmentId, null,
                "vm", vmId, vmName,
                "User: " + userId + ". Error: " + error);
    }

    // ============= User Operations =============

    public void logUserCreated(String userId, String email) {
        logAction(auditUserId(userId), AuditAction.USER_CREATED, "user", userId, email,
                "User created via OAuth2 login: " + email);
    }

    public void logUserRoleChanged(String performedByUserId, String targetUserId, String role, boolean newValue) {
        String action = newValue ? "granted" : "revoked";
        logAction(auditUserId(performedByUserId), AuditAction.USER_UPDATED, "user", targetUserId, role,
                String.format("Role '%s' %s by user: %s", role, action, performedByUserId));
    }

    public void logUserDeactivated(String performedByUserId, String targetUserId) {
        logAction(auditUserId(performedByUserId), AuditAction.USER_DEACTIVATED, "user", targetUserId, targetUserId,
                "User deactivated by: " + performedByUserId);
    }

    public void logUserReactivated(String performedByUserId, String targetUserId) {
        logAction(auditUserId(performedByUserId), AuditAction.USER_UPDATED, "user", targetUserId, targetUserId,
                "User reactivated by: " + performedByUserId);
    }

    // ============= Access Request Operations =============

    public void logAccessRequested(String userId, String environmentId, String environmentName, String accessLevel) {
        logEnvironmentAction(auditUserId(userId), AuditAction.ACCESS_REQUESTED, environmentId, environmentName,
                "environment_access", environmentId, environmentName,
                String.format("Access requested by user: %s. Level: %s", userId, accessLevel));
    }

    public void logAccessGranted(String reviewerId, String userId, String environmentId, String environmentName, String accessLevel) {
        logEnvironmentAction(auditUserId(reviewerId), AuditAction.ACCESS_GRANTED, environmentId, environmentName,
                "environment_access", environmentId, environmentName,
                String.format("Access granted to user: %s. Level: %s. Approved by: %s", userId, accessLevel, reviewerId));
    }

    public void logAccessDenied(String reviewerId, String userId, String environmentId, String environmentName, String reason) {
        logEnvironmentAction(auditUserId(reviewerId), AuditAction.ACCESS_DENIED, environmentId, environmentName,
                "environment_access", environmentId, environmentName,
                String.format("Access denied for user: %s. Denied by: %s. Reason: %s", userId, reviewerId, reason));
    }

    public void logAccessRevoked(String performedByUserId, String userId, String environmentId, String environmentName) {
        logEnvironmentAction(auditUserId(performedByUserId), AuditAction.ACCESS_REVOKED, environmentId, environmentName,
                "environment_access", environmentId, environmentName,
                String.format("Access revoked for user: %s. Revoked by: %s", userId, performedByUserId));
    }

    // ============= Inner Classes =============

    public static class EnvironmentActivitySummary {
        private final String environmentId;
        private final String environmentName;
        private final Long actionCount;

        public EnvironmentActivitySummary(String environmentId, String environmentName, Long actionCount) {
            this.environmentId = environmentId;
            this.environmentName = environmentName;
            this.actionCount = actionCount;
        }

        public String getEnvironmentId() {
            return environmentId;
        }

        public String getEnvironmentName() {
            return environmentName;
        }

        public Long getActionCount() {
            return actionCount;
        }
    }
}

