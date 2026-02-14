package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
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

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
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
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc();
    }

    /**
     * Get audit logs for an environment.
     */
    public Page<AuditLog> getLogsForEnvironment(String environmentId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findByEnvironmentIdOrderByCreatedAtDesc(environmentId, pageable);
    }

    /**
     * Get audit logs for a user.
     */
    public Page<AuditLog> getLogsForUser(String userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Get audit logs for a specific target.
     */
    public Page<AuditLog> getLogsForTarget(String targetType, String targetId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable);
    }

    /**
     * Get audit logs by action type.
     */
    public Page<AuditLog> getLogsByAction(AuditAction action, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action.name(), pageable);
    }

    /**
     * Get audit logs within date range.
     */
    public Page<AuditLog> getLogsInDateRange(LocalDate startDate, LocalDate endDate, int page, int size) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
    }

    /**
     * Get failed operations.
     */
    public Page<AuditLog> getFailedOperations(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findFailedOperations(pageable);
    }

    /**
     * Search audit logs.
     */
    public Page<AuditLog> searchLogs(String searchText, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.searchByDetails(searchText, pageable);
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
        return auditLogRepository.findLockOperationsInRange(start, end);
    }

    /**
     * Get VM operations for compliance.
     */
    public List<AuditLog> getVmOperationsReport(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return auditLogRepository.findVmOperationsInRange(start, end);
    }

    // ============= Convenience Methods for Common Actions =============

    public void logEnvironmentCreated(String userId, String environmentId, String environmentName) {
        // Don't set user_id to avoid FK constraint violations
        logAction(null, AuditAction.ENVIRONMENT_CREATED, "environment", environmentId, environmentName,
                "Environment created: " + environmentName + " by user: " + userId);
    }

    public void logGroupCreated(String userId, String environmentId, String environmentName,
                                String groupId, String groupName) {
        logAction(null, AuditAction.GROUP_CREATED, "group", groupId, groupName,
                "Group created: " + groupName + " in environment: " + environmentName + " by user: " + userId);
    }

    public void logVmRegistered(String userId, String environmentId, String environmentName,
                                String vmId, String vmName) {
        logAction(null, AuditAction.VM_REGISTERED, "vm", vmId, vmName,
                "VM registered: " + vmName + " in environment: " + environmentName + " by user: " + userId);
    }

    public void logLockAcquired(String userId, String environmentId, String environmentName, String reason) {
        // Don't set user_id or environment_id to avoid FK constraint violations
        // Store details in the details field instead
        logAction(null, AuditAction.LOCK_ACQUIRED, "lock", environmentId, environmentName,
                "Lock acquired by user: " + userId + ". Environment: " + environmentName + ". Reason: " + reason);
    }

    public void logLockReleased(String userId, String environmentId, String environmentName) {
        logAction(null, AuditAction.LOCK_RELEASED, "lock", environmentId, environmentName,
                "Lock released by user: " + userId + ". Environment: " + environmentName);
    }

    public void logLockBroken(String adminId, String environmentId, String environmentName,
                              String originalHolder, String reason) {
        logAction(null, AuditAction.LOCK_BROKEN, "lock", environmentId, environmentName,
                "Lock broken by admin: " + adminId + ". Original holder: " + originalHolder + ". Reason: " + reason);
    }

    public void logOperationStarted(String userId, String environmentId, String environmentName,
                                    String executionId, String operationType) {
        logAction(null, AuditAction.OPERATION_STARTED, "operation", executionId, operationType,
                "Operation started by user: " + userId + ". Environment: " + environmentName);
    }

    public void logOperationCompleted(String userId, String environmentId, String environmentName,
                                      String executionId, String operationType, int totalVms, int failed) {
        String details = String.format("Operation completed by user: %s. Environment: %s. Total VMs: %d, Failed: %d",
                userId, environmentName, totalVms, failed);
        logAction(null, AuditAction.OPERATION_COMPLETED, "operation", executionId, operationType, details);
    }

    public void logOperationFailed(String userId, String environmentId, String environmentName,
                                   String executionId, String operationType, String errorMessage) {
        logFailure(null, AuditAction.OPERATION_FAILED, "operation", executionId, operationType,
                "User: " + userId + ". Environment: " + environmentName + ". Error: " + errorMessage);
    }

    public void logVmStarted(String userId, String vmId, String vmName, String environmentId) {
        logAction(null, AuditAction.VM_START_COMPLETED, "vm", vmId, vmName,
                "VM started successfully by user: " + userId);
    }

    public void logVmStopped(String userId, String vmId, String vmName, String environmentId) {
        logAction(null, AuditAction.VM_STOP_COMPLETED, "vm", vmId, vmName,
                "VM stopped successfully by user: " + userId);
    }

    public void logVmStartFailed(String userId, String vmId, String vmName, String environmentId, String error) {
        logFailure(null, AuditAction.VM_START_FAILED, "vm", vmId, vmName,
                "User: " + userId + ". Error: " + error);
    }

    public void logVmStopFailed(String userId, String vmId, String vmName, String environmentId, String error) {
        logFailure(null, AuditAction.VM_STOP_FAILED, "vm", vmId, vmName,
                "User: " + userId + ". Error: " + error);
    }

    // ============= User Operations =============

    public void logUserCreated(String userId, String email) {
        logAction(null, AuditAction.USER_CREATED, "user", userId, email,
                "User created via OAuth2 login: " + email);
    }

    public void logUserRoleChanged(String performedByUserId, String targetUserId, String role, boolean newValue) {
        String action = newValue ? "granted" : "revoked";
        logAction(null, AuditAction.USER_UPDATED, "user", targetUserId, role,
                String.format("Role '%s' %s by user: %s", role, action, performedByUserId));
    }

    public void logUserDeactivated(String performedByUserId, String targetUserId) {
        logAction(null, AuditAction.USER_DEACTIVATED, "user", targetUserId, targetUserId,
                "User deactivated by: " + performedByUserId);
    }

    public void logUserReactivated(String performedByUserId, String targetUserId) {
        logAction(null, AuditAction.USER_UPDATED, "user", targetUserId, targetUserId,
                "User reactivated by: " + performedByUserId);
    }

    // ============= Access Request Operations =============

    public void logAccessRequested(String userId, String environmentId, String environmentName, String accessLevel) {
        logAction(null, AuditAction.ACCESS_REQUESTED, "environment_access", environmentId, environmentName,
                String.format("Access requested by user: %s. Level: %s", userId, accessLevel));
    }

    public void logAccessGranted(String reviewerId, String userId, String environmentId, String environmentName, String accessLevel) {
        logAction(null, AuditAction.ACCESS_GRANTED, "environment_access", environmentId, environmentName,
                String.format("Access granted to user: %s. Level: %s. Approved by: %s", userId, accessLevel, reviewerId));
    }

    public void logAccessDenied(String reviewerId, String userId, String environmentId, String environmentName, String reason) {
        logAction(null, AuditAction.ACCESS_DENIED, "environment_access", environmentId, environmentName,
                String.format("Access denied for user: %s. Denied by: %s. Reason: %s", userId, reviewerId, reason));
    }

    public void logAccessRevoked(String performedByUserId, String userId, String environmentId, String environmentName) {
        logAction(null, AuditAction.ACCESS_REVOKED, "environment_access", environmentId, environmentName,
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

