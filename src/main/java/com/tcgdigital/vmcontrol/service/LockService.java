package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException;
import com.tcgdigital.vmcontrol.exception.NoActiveLockException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.LockHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for environment lock management.
 * Handles lock acquisition, release, and breaking with concurrency control.
 */
@Service
public class LockService {

    private static final Logger log = LoggerFactory.getLogger(LockService.class);

    private final EnvironmentLockRepository lockRepository;
    private final LockHistoryRepository historyRepository;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;

    public LockService(EnvironmentLockRepository lockRepository,
                       LockHistoryRepository historyRepository,
                       EnvironmentRepository environmentRepository,
                       AuditService auditService) {
        this.lockRepository = lockRepository;
        this.historyRepository = historyRepository;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
    }

    /**
     * Acquire lock on environment.
     * Uses database-level checking to prevent concurrent acquisition.
     */
    @Transactional
    public EnvironmentLock acquireLock(String environmentId, String userId, String reason, Integer expectedDurationMinutes) {
        // Verify environment exists
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));

        // Check if environment is already locked
        Optional<EnvironmentLock> existingLock = lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId);

        if (existingLock.isPresent()) {
            EnvironmentLock activeLock = existingLock.get();

            // If same user holds the lock, just return it
            if (activeLock.getLockedByUserId().equals(userId)) {
                log.info("User {} already holds lock on environment {}", userId, environmentId);
                return activeLock;
            }

            // Lock held by another user
            throw new LockAlreadyHeldException(
                    "Environment is locked by user " + activeLock.getLockedByUserId() +
                            " since " + activeLock.getLockedAt(),
                    environmentId,
                    activeLock.getLockedByUserId()
            );
        }

        // Acquire new lock
        EnvironmentLock lock = new EnvironmentLock();
        lock.setLockId(UUID.randomUUID().toString());
        lock.setEnvironment(environment);
        lock.setLockedByUserId(userId);
        lock.setLockReason(reason);
        lock.setExpectedDurationMinutes(expectedDurationMinutes);
        lock.setIsActive(true);

        lock = lockRepository.save(lock);

        // Record history
        recordLockHistory(lock, LockAction.ACQUIRED, userId, "Reason: " + reason);

        // Audit logging
        auditService.logLockAcquired(userId, environmentId, environment.getName(), reason);

        log.info("Lock acquired on environment {} by user {}", environmentId, userId);

        return lock;
    }

    /**
     * Release lock.
     */
    @Transactional
    public void releaseLock(String environmentId, String userId) {
        EnvironmentLock lock = lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId)
                .orElseThrow(() -> new NoActiveLockException("No active lock on environment", environmentId));

        // Verify user holds the lock
        if (!lock.getLockedByUserId().equals(userId)) {
            throw new UnauthorizedException("You do not hold the lock on this environment");
        }

        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setReleasedByUserId(userId);

        lockRepository.save(lock);

        // Record history
        recordLockHistory(lock, LockAction.RELEASED, userId, null);

        // Audit logging
        auditService.logLockReleased(userId, environmentId, lock.getEnvironment().getName());

        log.info("Lock released on environment {} by user {}", environmentId, userId);
    }

    /**
     * Admin/Env-Admin breaks lock (emergency).
     */
    @Transactional
    public void breakLock(String environmentId, String adminUserId, String breakReason) {
        EnvironmentLock lock = lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId)
                .orElseThrow(() -> new NoActiveLockException("No active lock to break", environmentId));

        String originalLockHolder = lock.getLockedByUserId();

        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setBrokenByAdminUserId(adminUserId);
        lock.setBreakReason(breakReason);

        lockRepository.save(lock);

        // Record history
        recordLockHistory(lock, LockAction.BROKEN, adminUserId,
                "Original holder: " + originalLockHolder + ". Reason: " + breakReason);

        // Audit logging
        auditService.logLockBroken(adminUserId, environmentId, lock.getEnvironment().getName(),
                originalLockHolder, breakReason);

        log.warn("Lock on environment {} broken by admin {} (was held by {}). Reason: {}",
                environmentId, adminUserId, originalLockHolder, breakReason);

        // TODO: Send notification to original lock holder when notification service is implemented
    }

    /**
     * Check if environment is locked.
     */
    public boolean isEnvironmentLocked(String environmentId) {
        return lockRepository.existsByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId);
    }

    /**
     * Get current lock holder.
     */
    public Optional<EnvironmentLock> getCurrentLock(String environmentId) {
        return lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId);
    }

    /**
     * Verify user can perform operation (has lock or no lock exists).
     */
    public void verifyLockPermission(String environmentId, String userId) {
        Optional<EnvironmentLock> lock = lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId);

        if (lock.isEmpty()) {
            // No lock - OK to proceed
            return;
        }

        // Lock exists - verify user holds it
        if (!lock.get().getLockedByUserId().equals(userId)) {
            throw new LockAlreadyHeldException(
                    "Environment is locked by another user: " + lock.get().getLockedByUserId(),
                    environmentId,
                    lock.get().getLockedByUserId()
            );
        }
    }

    /**
     * Get all active locks (for admin view).
     */
    public List<EnvironmentLock> getAllActiveLocks() {
        return lockRepository.findByIsActiveTrue();
    }

    /**
     * Get lock history for an environment.
     */
    public List<LockHistory> getLockHistory(String environmentId) {
        return historyRepository.findTop20ByEnvironmentIdOrderByPerformedAtDesc(environmentId);
    }

    /**
     * Get locks held by a user.
     */
    public List<EnvironmentLock> getLocksHeldByUser(String userId) {
        return lockRepository.findByLockedByUserIdAndIsActiveTrue(userId);
    }

    // ============= Private Helper Methods =============

    private void recordLockHistory(EnvironmentLock lock, LockAction action, String userId, String notes) {
        LockHistory history = new LockHistory();
        history.setHistoryId(UUID.randomUUID().toString());
        history.setLock(lock);
        history.setEnvironmentId(lock.getEnvironment().getEnvironmentId());
        history.setAction(action);
        history.setPerformedByUserId(userId);
        history.setNotes(notes);

        historyRepository.save(history);
    }
}

