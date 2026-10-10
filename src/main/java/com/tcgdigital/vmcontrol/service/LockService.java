package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException;
import com.tcgdigital.vmcontrol.exception.NoActiveLockException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.LockHistoryRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final NotificationService notificationService;
    private final AutomationRuleService automationRuleService;
    private final UserRepository userRepository;
    private final AfterCommit afterCommit;
    /** Whether a holder may still hold a lock (E07-T04); lazy: SecurityService reaches back here. */
    private final SecurityService securityService;
    /** The acquire itself: joins the caller's transaction, if any. */
    private final org.springframework.transaction.support.TransactionTemplate acquireTransaction;
    /** Reading the lock that won a race: a fresh snapshot that sees it. */
    private final org.springframework.transaction.support.TransactionTemplate freshRead;
    @org.springframework.beans.factory.annotation.Value("${locks.max-duration-minutes:1440}")
    private long maxDurationMinutes = 1440;

    /** Minutes before expiry at which the holder is warned (E07-T03). */
    @org.springframework.beans.factory.annotation.Value("${locks.expiry.warning-minutes:15,5}")
    private List<Integer> warningMinutes = List.of(15, 5);

    /** One expiry, committed on its own: one failing lock does not stop the sweep. */
    private final org.springframework.transaction.support.TransactionTemplate expiryTransaction;

    public LockService(EnvironmentLockRepository lockRepository,
                       LockHistoryRepository historyRepository,
                       EnvironmentRepository environmentRepository,
                       AuditService auditService,
                       NotificationService notificationService,
                       @Lazy AutomationRuleService automationRuleService,
                       UserRepository userRepository,
                       AfterCommit afterCommit,
                       @Lazy SecurityService securityService,
                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.securityService = securityService;
        this.acquireTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.freshRead = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.freshRead.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshRead.setReadOnly(true);
        this.expiryTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.expiryTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.userRepository = userRepository;
        this.afterCommit = afterCommit;
        this.lockRepository = lockRepository;
        this.historyRepository = historyRepository;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.automationRuleService = automationRuleService;
    }

    /**
     * Acquire lock on environment. The unique index ux_environment_lock_one_active (V36) decides a
     * race between simultaneous acquires (M1): the loser's transaction rolls back and it gets a
     * LockAlreadyHeldException naming the winner (409); the same user's concurrent request gets
     * the lock that won. The insert stays in the caller's transaction, so a rolled-back caller
     * leaves no lock behind.
     */
    public EnvironmentLock acquireLock(String environmentId, String userId, String reason, Integer expectedDurationMinutes) {
        try {
            return acquireTransaction.execute(status -> doAcquire(environmentId, userId, reason, expectedDurationMinutes));
        } catch (DataIntegrityViolationException raceLost) {
            EnvironmentLock winner = freshRead.execute(status ->
                    lockRepository.findByEnvironmentIdWithEnvironment(environmentId).orElse(null));
            if (winner == null) {
                throw raceLost;
            }
            if (winner.getLockedByUserId().equals(userId)) {
                log.info("User {} acquired the lock on environment {} twice at once; returning it", userId, environmentId);
                return winner; // the same user's concurrent request (e.g. a double click) won
            }
            throw new LockAlreadyHeldException(
                    "Environment was just locked by " + holderName(winner.getLockedByUserId()),
                    environmentId, winner.getLockedByUserId());
        }
    }

    private EnvironmentLock doAcquire(String environmentId, String userId, String reason, Integer expectedDurationMinutes) {
        // Verify environment exists
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        if (!Boolean.TRUE.equals(environment.getIsActive())) {
            throw new ValidationException("Environment is inactive and cannot be locked");
        }

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
                    "Environment is locked by " + holderName(activeLock.getLockedByUserId()) +
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
        if (expectedDurationMinutes != null) {
            // From the clock, not the DB default for locked_at; null = until manually released (E07-T02).
            lock.setExpiresAt(Timestamp.from(Instant.now().plus(java.time.Duration.ofMinutes(expectedDurationMinutes))));
        }
        lock.setIsActive(true);

        // Flushed now: a lost race fails here, on ux_environment_lock_one_active (M1).
        lock = lockRepository.saveAndFlush(lock);

        // Record history
        recordLockHistory(lock, LockAction.ACQUIRED, userId, "Reason: " + reason);

        // Audit logging
        auditService.logLockAcquired(userId, environmentId, environment.getName(), reason);

        // After commit: a failing notification never rolls back the lock change.
        afterCommit.run(() -> runNotificationSideEffect("notify lock acquired", environmentId, () ->
                notificationService.notifyLockAcquiredForEnvironment(
                        environmentId,
                        environment.getName(),
                        userId,
                        reason)));

        // After commit (C5): a failing rule must never roll back the lock it was triggered by,
        // and the operation it starts must see the committed lock.
        afterCommit.run(() -> runNotificationSideEffect("trigger lock-acquire automation rules", environmentId, () ->
                automationRuleService.handleLockAcquired(environmentId, userId)));

        log.info("Lock acquired on environment {} by user {}", environmentId, userId);

        return lock;
    }

    /**
     * Release lock.
     */
    @Transactional
    public void releaseLock(String environmentId, String userId) {
        log.debug("Attempting to release lock on environment {} by user {}", environmentId, userId);
        
        EnvironmentLock lock = lockRepository.findByEnvironmentIdWithEnvironment(environmentId)
                .orElseThrow(() -> {
                    log.warn("No active lock found for environment {} when user {} tried to release", environmentId, userId);
                    return new NoActiveLockException("No active lock on environment", environmentId);
                });

        log.debug("Found active lock {} held by user {}", lock.getLockId(), lock.getLockedByUserId());

        // Verify user holds the lock
        if (!lock.getLockedByUserId().equals(userId)) {
            log.warn("User {} attempted to release lock held by {}", userId, lock.getLockedByUserId());
            throw new UnauthorizedException("You do not hold the lock on this environment");
        }

        // Store environment name before modifying lock state
        String environmentName = lock.getEnvironment().getName();

        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setReleasedByUserId(userId);

        lockRepository.save(lock);

        // Record history
        recordLockHistory(lock, LockAction.RELEASED, userId, null);

        // Audit logging
        auditService.logLockReleased(userId, environmentId, environmentName);

        afterCommit.run(() -> runNotificationSideEffect("notify lock released", environmentId, () ->
                notificationService.notifyLockReleasedForEnvironment(
                        environmentId,
                        environmentName,
                        userId)));

        log.info("Lock released on environment {} by user {}", environmentId, userId);
    }

    /**
     * Admin/Env-Admin breaks lock (emergency).
     */
    @Transactional
    public void breakLock(String environmentId, String adminUserId, String breakReason) {
        EnvironmentLock lock = lockRepository.findByEnvironmentIdWithEnvironment(environmentId)
                .orElseThrow(() -> new NoActiveLockException("No active lock to break", environmentId));

        String originalLockHolder = lock.getLockedByUserId();
        String environmentName = lock.getEnvironment().getName();

        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setBrokenByAdminUserId(adminUserId);
        lock.setBreakReason(breakReason);

        lockRepository.save(lock);

        // Record history
        recordLockHistory(lock, LockAction.BROKEN, adminUserId,
                "Original holder: " + originalLockHolder + ". Reason: " + breakReason);

        // Audit logging
        auditService.logLockBroken(adminUserId, environmentId, environmentName,
                originalLockHolder, breakReason);

        log.warn("Lock on environment {} broken by admin {} (was held by {}). Reason: {}",
                environmentId, adminUserId, originalLockHolder, breakReason);

        afterCommit.run(() -> runNotificationSideEffect("notify lock broken", environmentId, () ->
                notificationService.notifyLockBrokenForEnvironment(
                        environmentId,
                        environmentName,
                        adminUserId,
                        originalLockHolder,
                        breakReason)));
    }

    /**
     * Release every active lock held by {@code userId} (E07-T04, H4), e.g. when they are
     * deactivated. Joins the caller's transaction.
     *
     * @return how many locks were released
     */
    @Transactional
    public int releaseLocksForUser(String userId, String reason) {
        List<EnvironmentLock> locks = lockRepository.findByLockedByUserIdAndIsActiveTrue(userId);
        locks.forEach(lock -> autoRelease(lock, reason));
        return locks.size();
    }

    /**
     * Release the lock on {@code environmentId} if {@code userId} holds it and can no longer
     * operate there (E07-T04, H4): a revoked, expired or lowered grant. A holder who can still
     * operate, through another grant, a group grant or a global role, keeps it. Joins the caller's
     * transaction, so the grant change is already visible.
     *
     * @return true if the lock was released
     */
    @Transactional
    public boolean releaseIfHolderLostAccess(String environmentId, String userId, String reason) {
        EnvironmentLock lock = lockRepository.findByEnvironmentIdWithEnvironment(environmentId).orElse(null);
        if (lock == null || !lock.getLockedByUserId().equals(userId)) {
            return false;
        }
        User holder = userRepository.findById(userId).orElse(null);
        if (holder != null && Boolean.TRUE.equals(holder.getIsActive())
                && securityService.canOperateInEnvironment(holder, environmentId)) {
            return false;
        }
        autoRelease(lock, reason);
        return true;
    }

    private void autoRelease(EnvironmentLock lock, String reason) {
        String holderId = lock.getLockedByUserId();
        String environmentId = lock.getEnvironment().getEnvironmentId();
        String environmentName = lock.getEnvironment().getName();
        lock.setIsActive(false);
        lock.setReleasedAt(Timestamp.from(Instant.now()));
        lock.setReleasedByUserId(null);
        lockRepository.save(lock);
        // performed_by is NOT NULL: the holder whose lock ended.
        recordLockHistory(lock, LockAction.RELEASED, holderId, "Auto-released: " + reason);
        auditService.logLockAutoReleased(holderId, environmentId, environmentName, reason);
        afterCommit.run(() -> runNotificationSideEffect("notify lock released", environmentId, () ->
                notificationService.notifyLockReleasedForEnvironment(environmentId, environmentName, holderId)));
        log.info("Lock on environment {} held by {} auto-released: {}", environmentId, holderId, reason);
    }

    /**
     * Extend the caller's own lock by {@code minutes} (E07-T03): from its current expiry, or from
     * now if that has already passed. Only the holder may extend; a lock without an expiry cannot
     * be extended; the total since acquisition may not exceed locks.max-duration-minutes.
     */
    @Transactional
    public EnvironmentLock extend(String environmentId, String userId, int minutes) {
        EnvironmentLock lock = lockRepository.findByEnvironmentIdWithLock(environmentId)
                .orElseThrow(() -> new NoActiveLockException("No active lock on environment", environmentId));
        if (!lock.getLockedByUserId().equals(userId)) {
            throw new UnauthorizedException("Only the lock holder can extend the lock");
        }
        if (lock.getExpiresAt() == null) {
            throw new ValidationException("This lock has no expiry");
        }
        Instant now = Instant.now();
        Instant base = lock.getExpiresAt().toInstant().isAfter(now) ? lock.getExpiresAt().toInstant() : now;
        Instant newExpiry = base.plus(java.time.Duration.ofMinutes(minutes));
        if (java.time.Duration.between(lock.getLockedAt().toInstant(), newExpiry).toMinutes() > maxDurationMinutes) {
            throw new ValidationException("A lock can last at most " + maxDurationMinutes
                    + " minutes from when it was taken; release it and lock again if you need longer");
        }
        lock.setExpiresAt(Timestamp.from(newExpiry));
        lock = lockRepository.save(lock);
        String notes = "Extended by " + minutes + " minutes to " + newExpiry;
        recordLockHistory(lock, LockAction.EXTENDED, userId, notes);
        auditService.logLockExtended(userId, environmentId, lock.getEnvironment().getName(), notes);
        log.info("Lock on environment {} extended by {} to {}", environmentId, userId, newExpiry);
        return lock;
    }

    /**
     * Warn holders whose locks expire within each locks.expiry.warning-minutes threshold
     * (E07-T03). Each threshold warns at most once per lock; an extension re-arms the warnings.
     *
     * @return how many warnings were sent
     */
    public int processExpiringLockWarnings() {
        Instant now = Instant.now();
        int sent = 0;
        List<Integer> thresholds = warningMinutes.stream().sorted(java.util.Comparator.reverseOrder()).toList();
        for (Integer threshold : thresholds) {
            for (EnvironmentLock lock : lockRepository.findActiveExpiringBetween(Timestamp.from(now),
                    Timestamp.from(now.plus(java.time.Duration.ofMinutes(threshold))))) {
                Instant windowStart = lock.getExpiresAt().toInstant().minus(java.time.Duration.ofMinutes(threshold));
                Instant since = windowStart.isAfter(lock.getLockedAt().toInstant()) ? windowStart : lock.getLockedAt().toInstant();
                try {
                    if (notificationService.notifyLockExpiring(lock.getLockedByUserId(),
                            lock.getEnvironment().getEnvironmentId(), lock.getEnvironment().getName(),
                            lock.getExpiresAt().toInstant(), java.time.ZoneId.systemDefault(), Timestamp.from(since))) {
                        sent++;
                    }
                } catch (Exception e) {
                    log.warn("Could not warn about expiring lock {}: {}", lock.getLockId(), e.getMessage());
                }
            }
        }
        return sent;
    }

    /**
     * Release every active lock past its expiry (E07-T02, H4). Each lock is released in its own
     * transaction under a row lock, re-checked first, so a second instance (or a release that
     * happened meanwhile) makes it a no-op. Locks that expired while the app was down are released
     * on the first sweep.
     *
     * @return how many locks were released
     */
    public int processExpiredLocks() {
        int released = 0;
        for (String lockId : lockRepository.findExpiredActiveLockIds(Timestamp.from(Instant.now()))) {
            try {
                if (expireLock(lockId)) {
                    released++;
                }
            } catch (Exception e) {
                log.error("Could not expire lock {}: {}", lockId, e.getMessage(), e);
            }
        }
        if (released > 0) {
            log.info("Lock expiry released {} lock(s)", released);
        }
        return released;
    }

    /** Release one lock if it is still active and past its expiry; false otherwise (idempotent). */
    public boolean expireLock(String lockId) {
        Boolean expired = expiryTransaction.execute(status -> {
            EnvironmentLock lock = lockRepository.findByIdForUpdate(lockId).orElse(null);
            Instant now = Instant.now();
            if (lock == null || !Boolean.TRUE.equals(lock.getIsActive()) || lock.getExpiresAt() == null
                    || lock.getExpiresAt().toInstant().isAfter(now)) {
                return false;
            }
            String holderId = lock.getLockedByUserId();
            String environmentId = lock.getEnvironment().getEnvironmentId();
            String environmentName = lock.getEnvironment().getName();
            long minutes = java.time.Duration.between(lock.getLockedAt().toInstant(), lock.getExpiresAt().toInstant()).toMinutes();
            lock.setIsActive(false);
            lock.setReleasedAt(Timestamp.from(now));
            lock.setReleasedByUserId(null);
            lockRepository.save(lock);
            // performed_by is NOT NULL: the holder whose lock ended.
            recordLockHistory(lock, LockAction.EXPIRED, holderId, "Expired after " + minutes + " minutes");
            auditService.logLockExpired(holderId, environmentId, environmentName, "Expired after " + minutes + " minutes");
            afterCommit.run(() -> runNotificationSideEffect("notify lock expired", environmentId, () ->
                    notificationService.notifyLockExpiredForEnvironment(environmentId, environmentName, holderId)));
            log.info("Lock on environment {} held by {} expired after {} minutes", environmentId, holderId, minutes);
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    /** A lock holder's display name (or email) for messages; never their raw user id. */
    private String holderName(String userId) {
        return userRepository.findById(userId)
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail())
                .orElse("another user");
    }

    private void runNotificationSideEffect(String action, String environmentId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for environment {}: {}", action, environmentId, e.getMessage());
        }
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
            // Name the holder, not their user id (LOW-OPS-403-TOAST); callers still get the id.
            String holder = userRepository.findById(lock.get().getLockedByUserId())
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail())
                    .orElse("another user");
            throw new LockAlreadyHeldException(
                    "Environment is locked by another user: " + holder,
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

