package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * Repository for AuditLog entity.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    /**
     * Find audit logs by user.
     */
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * Find audit logs by environment.
     */
    Page<AuditLog> findByEnvironmentIdOrderByCreatedAtDesc(String environmentId, Pageable pageable);

    /**
     * Find audit logs by target.
     */
    Page<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId, Pageable pageable);

    /**
     * Find audit logs by action type.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.action = :action ORDER BY a.createdAt DESC")
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    /**
     * Find audit logs within time range.
     */
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(Timestamp startTime, Timestamp endTime, Pageable pageable);

    /**
     * Find audit logs by environment and time range.
     */
    Page<AuditLog> findByEnvironmentIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String environmentId, Timestamp startTime, Timestamp endTime, Pageable pageable);

    /**
     * Find recent audit logs.
     */
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    /**
     * Find recent audit logs for an environment.
     */
    List<AuditLog> findTop50ByEnvironmentIdOrderByCreatedAtDesc(String environmentId);

    /**
     * Find failed operations.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.actionStatus = 'failed' ORDER BY a.createdAt DESC")
    Page<AuditLog> findFailedOperations(Pageable pageable);

    /**
     * Count actions by type within time range.
     */
    @Query("SELECT a.action, COUNT(a) FROM AuditLog a WHERE a.createdAt BETWEEN :startTime AND :endTime GROUP BY a.action")
    List<Object[]> countActionsByTypeInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Count actions by user within time range.
     */
    @Query("SELECT a.userId, COUNT(a) FROM AuditLog a WHERE a.createdAt BETWEEN :startTime AND :endTime GROUP BY a.userId ORDER BY COUNT(a) DESC")
    List<Object[]> countActionsByUserInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Count actions by environment within time range.
     */
    @Query("SELECT a.environmentId, COUNT(a) FROM AuditLog a WHERE a.createdAt BETWEEN :startTime AND :endTime AND a.environmentId IS NOT NULL GROUP BY a.environmentId ORDER BY COUNT(a) DESC")
    List<Object[]> countActionsByEnvironmentInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Find lock operations for compliance reporting.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.action IN ('LOCK_ACQUIRED', 'LOCK_RELEASED', 'LOCK_BROKEN') AND a.createdAt BETWEEN :startTime AND :endTime ORDER BY a.createdAt DESC")
    List<AuditLog> findLockOperationsInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Find VM operations for compliance reporting.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.action IN ('VM_START_REQUESTED', 'VM_STOP_REQUESTED', 'VM_START_COMPLETED', 'VM_STOP_COMPLETED', 'VM_START_FAILED', 'VM_STOP_FAILED') AND a.createdAt BETWEEN :startTime AND :endTime ORDER BY a.createdAt DESC")
    List<AuditLog> findVmOperationsInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Search audit logs by details containing text.
     */
    @Query("SELECT a FROM AuditLog a WHERE LOWER(a.details) LIKE LOWER(CONCAT('%', :searchText, '%')) ORDER BY a.createdAt DESC")
    Page<AuditLog> searchByDetails(String searchText, Pageable pageable);
}
