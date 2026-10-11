package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.dto.LeasePolicyDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.support.AuditChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Environment leases (E15): every opted-in running environment has an end time. This class
 * holds the policy (platform limits from lease.*, per-environment default and maximum) and reads
 * leases; later tasks add opening, extending and expiring them. Everything is a no-op or a 404
 * while lease.enabled is false.
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    /** Shortest lease an owner may configure. */
    public static final int MIN_MINUTES = 15;

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentLeaseRepository leaseRepository;
    private final EnvironmentLeaseParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final AuditService auditService;
    private final UserService userService;

    private final boolean featureEnabled;
    private final int platformDefaultMinutes;
    private final int platformMaxMinutes;
    private final int platformAbsoluteMaxMinutes;

    @Value("${lease.lock-release-grace-minutes:30}")
    private int lockReleaseGraceMinutes = 30;

    @Value("${lease.participant-end-grace-minutes:15}")
    private int participantEndGraceMinutes = 15;

    @Autowired
    private com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository lockRepository;

    @Autowired
    private com.tcgdigital.vmcontrol.repository.LeaseExtensionRequestRepository extensionRepository;

    @Autowired
    private com.tcgdigital.vmcontrol.repository.OperationExecutionRepository executionRepository;

    @Autowired
    @Lazy
    private NotificationService notificationService;

    @Autowired
    private com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit;

    @Value("${lease.warning-minutes:60,15,5}")
    private List<Integer> warningMinutes = List.of(60, 15, 5);

    /** Opening a lease runs in its own transaction so a lost race on the unique index can join instead. */
    private TransactionTemplate newTransaction;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LeaseService(EnvironmentRepository environmentRepository,
                        EnvironmentLeaseRepository leaseRepository,
                        EnvironmentLeaseParticipantRepository participantRepository,
                        UserRepository userRepository,
                        @Lazy SecurityService securityService,
                        AuditService auditService,
                        UserService userService,
                        @Value("${lease.enabled:false}") boolean featureEnabled,
                        @Value("${lease.platform.default-minutes:240}") int platformDefaultMinutes,
                        @Value("${lease.platform.max-minutes:720}") int platformMaxMinutes,
                        @Value("${lease.platform.absolute-max-minutes:4320}") int platformAbsoluteMaxMinutes) {
        this.environmentRepository = environmentRepository;
        this.leaseRepository = leaseRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.auditService = auditService;
        this.userService = userService;
        this.featureEnabled = featureEnabled;
        this.platformDefaultMinutes = platformDefaultMinutes;
        this.platformMaxMinutes = platformMaxMinutes;
        this.platformAbsoluteMaxMinutes = platformAbsoluteMaxMinutes;
    }

    // ---------------------------------------------------------------- policy

    /** The master switch, lease.enabled. */
    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /** Leases apply to this environment: the feature is on and the owner opted it in. */
    public boolean isEnabledFor(Environment env) {
        return featureEnabled && env != null && Boolean.TRUE.equals(env.getLeaseEnabled());
    }

    /** The owner's default, or the platform default; never above the effective maximum. */
    public int effectiveDefault(Environment env) {
        int value = env.getLeaseDefaultMinutes() != null ? env.getLeaseDefaultMinutes() : platformDefaultMinutes;
        return Math.min(value, effectiveMax(env));
    }

    /** The owner's maximum, or the platform maximum; never above the platform maximum. */
    public int effectiveMax(Environment env) {
        int value = env.getLeaseMaxMinutes() != null ? env.getLeaseMaxMinutes() : platformMaxMinutes;
        return Math.min(value, platformMaxMinutes);
    }

    public int getPlatformAbsoluteMaxMinutes() {
        return platformAbsoluteMaxMinutes;
    }

    /** 404 for every lease endpoint while the feature is off. */
    public void assertFeatureEnabled() {
        if (!featureEnabled) {
            throw new ResourceNotFoundException("Leases", "feature disabled");
        }
    }

    @Transactional(readOnly = true)
    public LeasePolicyDTO getPolicy(String environmentId) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        return toPolicyDto(loadEnvironment(environmentId), securityService.canManageEnvironmentAccess(environmentId));
    }

    /**
     * Owners set the policy within the platform limits: {@value #MIN_MINUTES} <= default <= max <=
     * lease.platform.max-minutes (null minutes mean the platform value). Changes are audited.
     */
    @Transactional
    public LeasePolicyDTO updatePolicy(String environmentId, LeasePolicyDTO dto) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        if (!securityService.canManageEnvironmentAccess(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.UnauthorizedException(
                    "Only the environment's owners can change its lease policy");
        }
        Environment env = loadEnvironment(environmentId);
        validatePolicy(dto.getDefaultMinutes(), dto.getMaxMinutes());

        AuditChanges changes = new AuditChanges()
                .add("leaseEnabled", env.getLeaseEnabled(), dto.getEnabled())
                .add("leaseDefaultMinutes", env.getLeaseDefaultMinutes(), dto.getDefaultMinutes())
                .add("leaseMaxMinutes", env.getLeaseMaxMinutes(), dto.getMaxMinutes());
        env.setLeaseEnabled(Boolean.TRUE.equals(dto.getEnabled()));
        env.setLeaseDefaultMinutes(dto.getDefaultMinutes());
        env.setLeaseMaxMinutes(dto.getMaxMinutes());
        environmentRepository.save(env);
        if (!changes.isEmpty()) {
            auditService.logEnvironmentUpdated(userService.getCurrentUserId(), env.getEnvironmentId(), env.getName(),
                    changes.toString());
        }
        return toPolicyDto(env, true);
    }

    void validatePolicy(Integer defaultMinutes, Integer maxMinutes) {
        int max = maxMinutes != null ? maxMinutes : platformMaxMinutes;
        int def = defaultMinutes != null ? defaultMinutes : Math.min(platformDefaultMinutes, max);
        if (max > platformMaxMinutes) {
            throw new ValidationException("The maximum lease is " + platformMaxMinutes + " minutes on this platform");
        }
        if (def < MIN_MINUTES || max < MIN_MINUTES) {
            throw new ValidationException("A lease is at least " + MIN_MINUTES + " minutes");
        }
        if (def > max) {
            throw new ValidationException("The default lease cannot be longer than the maximum");
        }
    }

    private LeasePolicyDTO toPolicyDto(Environment env, boolean canEdit) {
        LeasePolicyDTO dto = new LeasePolicyDTO();
        dto.setEnabled(Boolean.TRUE.equals(env.getLeaseEnabled()));
        dto.setDefaultMinutes(env.getLeaseDefaultMinutes());
        dto.setMaxMinutes(env.getLeaseMaxMinutes());
        dto.setEffectiveDefaultMinutes(effectiveDefault(env));
        dto.setEffectiveMaxMinutes(effectiveMax(env));
        dto.setPlatformDefaultMinutes(platformDefaultMinutes);
        dto.setPlatformMaxMinutes(platformMaxMinutes);
        dto.setPlatformAbsoluteMaxMinutes(platformAbsoluteMaxMinutes);
        dto.setCanEdit(canEdit);
        return dto;
    }

    // ---------------------------------------------------------------- start / stop hooks (E15-T02)

    /** A START asking for more than the environment's maximum is a 400 (no operation is created). */
    public void validateRequestedMinutes(Environment env, Integer minutes) {
        if (minutes == null || !isEnabledFor(env)) return;
        if (minutes > effectiveMax(env)) {
            throw new ValidationException("This environment runs for at most " + effectiveMax(env)
                    + " minutes at a time");
        }
    }

    /**
     * After a START commits: open a lease ending {@code requestedMinutes} (or the owner's default)
     * from now, or join the active one and move its end later if needed — never past
     * {@code startedAt + effective maximum}. The starter becomes a participant.
     *
     * <p>Runs in a transaction of its own; when a concurrent start opened the lease first (the
     * unique index rejects the second insert) it retries once in a fresh transaction, whose new
     * snapshot sees that lease and joins it (under REPEATABLE READ the first one cannot).
     */
    public void openOrJoinOnStart(String environmentId, String userId, Integer requestedMinutes) {
        try {
            newTransaction.executeWithoutResult(status -> openOrJoinOnce(environmentId, userId, requestedMinutes));
        } catch (DataIntegrityViolationException e) {
            log.info("Lease for environment {} was opened concurrently; joining it", environmentId);
            newTransaction.executeWithoutResult(status -> openOrJoinOnce(environmentId, userId, requestedMinutes));
        }
    }

    private void openOrJoinOnce(String environmentId, String userId, Integer requestedMinutes) {
        Environment env = environmentRepository.findById(environmentId).orElse(null);
        if (!isEnabledFor(env)) return;
        int minutes = Math.min(requestedMinutes != null ? requestedMinutes : effectiveDefault(env), effectiveMax(env));
        Instant now = Instant.now();

        Optional<EnvironmentLease> active = leaseRepository.findActiveByEnvironmentId(environmentId);
        if (active.isEmpty()) {
            EnvironmentLease lease = new EnvironmentLease();
            lease.setLeaseId(UUID.randomUUID().toString());
            lease.setEnvironment(env);
            lease.setStatus(com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE);
            lease.setSource(com.tcgdigital.vmcontrol.model.LeaseSource.START);
            lease.setStartedByUserId(userId);
            lease.setStartedAt(Timestamp.from(now));
            lease.setEndsAt(Timestamp.from(now.plus(Duration.ofMinutes(minutes))));
            leaseRepository.saveAndFlush(lease); // a concurrent open fails here on the unique index
            participantRepository.save(new EnvironmentLeaseParticipant(lease.getLeaseId(), userId, Timestamp.from(now)));
            auditService.logLeaseStarted(userId, environmentId, env.getName(), lease.getLeaseId(),
                    "Runs for " + minutes + " minutes.");
            return;
        }
        EnvironmentLease lease = active.get();
        join(lease, userId, now);
        Instant cap = lease.getStartedAt().toInstant().plus(Duration.ofMinutes(effectiveMax(env)));
        Instant wanted = now.plus(Duration.ofMinutes(minutes));
        Instant newEnd = wanted.isAfter(cap) ? cap : wanted;
        if (newEnd.isAfter(lease.getEndsAt().toInstant())) {
            Instant oldEnd = lease.getEndsAt().toInstant();
            lease.setEndsAt(Timestamp.from(newEnd));
            leaseRepository.save(lease);
            auditService.logLeaseExtended(userId, environmentId, env.getName(), lease.getLeaseId(),
                    "End moved from " + oldEnd + " to " + newEnd + " on start.");
        }
    }

    /** Make the user an active participant (rejoining clears an earlier leave). */
    void join(EnvironmentLease lease, String userId, Instant now) {
        EnvironmentLeaseParticipant participant = participantRepository
                .findById(new com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipantId(lease.getLeaseId(), userId))
                .orElseGet(() -> new EnvironmentLeaseParticipant(lease.getLeaseId(), userId, Timestamp.from(now)));
        participant.setLeftAt(null);
        participant.setLeftReason(null);
        participantRepository.save(participant);
    }

    /** After a whole-environment STOP commits: the active lease ends (partial stops do not end it). */
    @Transactional
    public void endOnFullStop(String environmentId, String userId) {
        Environment env = environmentRepository.findById(environmentId).orElse(null);
        if (!isEnabledFor(env)) return;
        leaseRepository.findActiveByEnvironmentId(environmentId).ifPresent(lease -> {
            String who = userRepository.findById(userId).map(LeaseService::displayName).orElse("a user");
            lease.setStatus(com.tcgdigital.vmcontrol.model.LeaseStatus.ENDED);
            lease.setEndedAt(Timestamp.from(Instant.now()));
            lease.setEndReason("Stopped by " + who);
            leaseRepository.save(lease);
            auditService.logLeaseEnded(userId, environmentId, env.getName(), lease.getLeaseId(),
                    "Lease ended: environment stopped by " + who + ".");
        });
    }

    // ---------------------------------------------------------------- lock and access hooks (E15-T03)

    /**
     * After a lock with an expiry is taken or extended: the lock's duration becomes (or lengthens)
     * the lease. No active lease: open one (source LOCK) ending at the lock's expiry, capped at
     * now + effective maximum. Active lease: link the lock and move the end to the lock's expiry
     * if later, capped at startedAt + effective maximum. The holder becomes a participant. A lock
     * "until manually released" (no expiry) changes nothing.
     */
    public void onLockAcquiredOrExtended(String lockId) {
        try {
            newTransaction.executeWithoutResult(status -> applyLock(lockId));
        } catch (DataIntegrityViolationException e) {
            log.info("Lease for lock {} was opened concurrently; joining it", lockId);
            newTransaction.executeWithoutResult(status -> applyLock(lockId));
        }
    }

    private void applyLock(String lockId) {
        com.tcgdigital.vmcontrol.model.EnvironmentLock lock = lockRepository.findById(lockId).orElse(null);
        if (lock == null || !Boolean.TRUE.equals(lock.getIsActive()) || lock.getExpiresAt() == null) return;
        Environment env = lock.getEnvironment();
        if (!isEnabledFor(env)) return;
        String envId = env.getEnvironmentId();
        String holderId = lock.getLockedByUserId();
        Instant now = Instant.now();
        Instant lockEnd = lock.getExpiresAt().toInstant();

        Optional<EnvironmentLease> active = leaseRepository.findActiveByEnvironmentId(envId);
        if (active.isEmpty()) {
            Instant cap = now.plus(Duration.ofMinutes(effectiveMax(env)));
            EnvironmentLease lease = new EnvironmentLease();
            lease.setLeaseId(UUID.randomUUID().toString());
            lease.setEnvironment(env);
            lease.setStatus(com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE);
            lease.setSource(com.tcgdigital.vmcontrol.model.LeaseSource.LOCK);
            lease.setLockId(lockId);
            lease.setStartedByUserId(holderId);
            lease.setStartedAt(Timestamp.from(now));
            lease.setEndsAt(Timestamp.from(lockEnd.isAfter(cap) ? cap : lockEnd));
            leaseRepository.saveAndFlush(lease);
            participantRepository.save(new EnvironmentLeaseParticipant(lease.getLeaseId(), holderId, Timestamp.from(now)));
            auditService.logLeaseStarted(holderId, envId, env.getName(), lease.getLeaseId(),
                    "Follows the lock until " + lease.getEndsAt().toInstant() + ".");
            return;
        }
        EnvironmentLease lease = active.get();
        lease.setLockId(lockId);
        join(lease, holderId, now);
        Instant cap = lease.getStartedAt().toInstant().plus(Duration.ofMinutes(effectiveMax(env)));
        Instant wanted = lockEnd.isAfter(cap) ? cap : lockEnd;
        if (wanted.isAfter(lease.getEndsAt().toInstant())) {
            Instant oldEnd = lease.getEndsAt().toInstant();
            lease.setEndsAt(Timestamp.from(wanted));
            auditService.logLeaseExtended(holderId, envId, env.getName(), lease.getLeaseId(),
                    "End moved from " + oldEnd + " to " + wanted + " with the lock.");
        }
        leaseRepository.save(lease);
    }

    /**
     * After a lock ends (released, broken, expired or auto-released): if the active lease follows
     * that lock, it now ends within lease.lock-release-grace-minutes unless someone extends it, and
     * the holder leaves the lease.
     */
    @Transactional
    public void onLockEnded(String lockId, String environmentId, String holderId, String reason) {
        Environment env = environmentRepository.findById(environmentId).orElse(null);
        if (!isEnabledFor(env)) return;
        leaseRepository.findActiveByEnvironmentId(environmentId)
                .filter(lease -> lockId.equals(lease.getLockId()))
                .ifPresent(lease -> {
                    Instant now = Instant.now();
                    lease.setLockId(null);
                    Instant graceEnd = now.plus(Duration.ofMinutes(lockReleaseGraceMinutes));
                    if (graceEnd.isBefore(lease.getEndsAt().toInstant())) {
                        lease.setEndsAt(Timestamp.from(graceEnd));
                    }
                    leaseRepository.save(lease);
                    leave(lease.getLeaseId(), holderId, now, reason);
                });
    }

    /**
     * After a user's access to the environment ends (expired, revoked or lowered): if they can
     * no longer operate there they leave the lease; when nobody is left it ends within
     * lease.participant-end-grace-minutes.
     */
    @Transactional
    public void onUserAccessEnded(String environmentId, String userId, String reason) {
        Environment env = environmentRepository.findById(environmentId).orElse(null);
        if (!isEnabledFor(env)) return;
        Optional<EnvironmentLease> active = leaseRepository.findActiveByEnvironmentId(environmentId);
        if (active.isEmpty()) return;
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && Boolean.TRUE.equals(user.getIsActive())
                && securityService.canOperateInEnvironment(user, environmentId)) {
            return;
        }
        EnvironmentLease lease = active.get();
        Instant now = Instant.now();
        if (!leave(lease.getLeaseId(), userId, now, reason)) return;
        if (participantRepository.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(lease.getLeaseId()).isEmpty()) {
            Instant graceEnd = now.plus(Duration.ofMinutes(participantEndGraceMinutes));
            if (graceEnd.isBefore(lease.getEndsAt().toInstant())) {
                lease.setEndsAt(Timestamp.from(graceEnd));
                leaseRepository.save(lease);
            }
        }
    }

    /** Mark an active participant as left; false when they were not one. */
    private boolean leave(String leaseId, String userId, Instant now, String reason) {
        return participantRepository
                .findById(new com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipantId(leaseId, userId))
                .filter(p -> p.getLeftAt() == null)
                .map(p -> {
                    p.setLeftAt(Timestamp.from(now));
                    p.setLeftReason(reason == null ? null : reason.length() > 100 ? reason.substring(0, 100) : reason);
                    participantRepository.save(p);
                    return true;
                })
                .orElse(false);
    }

    // ---------------------------------------------------------------- extend and approve (E15-T04)

    /** What an extend call did: applied (200, the lease) or sent to the owners (202, the request). */
    public record ExtendOutcome(LeaseDTO lease, com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO request) {
        public boolean applied() {
            return lease != null;
        }
    }

    /**
     * Extend the running lease by {@code minutes} from its end (or from now if it already passed).
     * Within the owner's maximum it applies at once; beyond it (up to the platform's absolute
     * maximum) it becomes one pending request for the owners; beyond that it is a 400.
     */
    public ExtendOutcome extend(String environmentId, int minutes, String reason) {
        assertFeatureEnabled();
        securityService.assertCanOperate(environmentId);
        String userId = userService.getCurrentUserId();
        try {
            return newTransaction.execute(status -> extendOnce(environmentId, userId, minutes, reason));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Someone else changed the lease at the same moment: decide again on the fresh row.
            return newTransaction.execute(status -> extendOnce(environmentId, userId, minutes, reason));
        }
    }

    private ExtendOutcome extendOnce(String environmentId, String userId, int minutes, String reason) {
        EnvironmentLease lease = leaseRepository.findActiveByEnvironmentId(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Lease", "no active lease on " + environmentId));
        Environment env = lease.getEnvironment();
        Instant now = Instant.now();
        Instant base = lease.getEndsAt().toInstant().isAfter(now) ? lease.getEndsAt().toInstant() : now;
        Instant newEnd = base.plus(Duration.ofMinutes(minutes));
        long total = Duration.between(lease.getStartedAt().toInstant(), newEnd).toMinutes();

        if (total <= effectiveMax(env)) {
            Instant oldEnd = lease.getEndsAt().toInstant();
            lease.setEndsAt(Timestamp.from(newEnd));
            lease.setExtendedMinutesTotal(lease.getExtendedMinutesTotal() + minutes);
            leaseRepository.saveAndFlush(lease);
            join(lease, userId, now);
            auditService.logLeaseExtended(userId, environmentId, env.getName(), lease.getLeaseId(),
                    "Extended by " + minutes + " minutes: " + oldEnd + " -> " + newEnd + ".");
            return new ExtendOutcome(toDto(lease, permissionsFor(environmentId)), null);
        }
        if (total > platformAbsoluteMaxMinutes) {
            throw new ValidationException("An environment can run at most " + platformAbsoluteMaxMinutes
                    + " minutes in one lease, even with approval");
        }
        if (extensionRepository.findFirstByLeaseIdAndStatus(lease.getLeaseId(),
                com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING).isPresent()) {
            throw new com.tcgdigital.vmcontrol.exception.ConflictException(
                    "An extension request is already waiting for the owner's decision");
        }
        com.tcgdigital.vmcontrol.model.LeaseExtensionRequest request = new com.tcgdigital.vmcontrol.model.LeaseExtensionRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setLeaseId(lease.getLeaseId());
        request.setRequesterUserId(userId);
        request.setMinutes(minutes);
        request.setReason(reason);
        request.setStatus(com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING);
        request.setCreatedAt(Timestamp.from(now));
        try {
            extensionRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            throw new com.tcgdigital.vmcontrol.exception.ConflictException(
                    "An extension request is already waiting for the owner's decision");
        }
        auditService.logEnvironmentAction(userId, com.tcgdigital.vmcontrol.model.AuditAction.LEASE_EXTENSION_REQUESTED,
                environmentId, env.getName(), "lease", lease.getLeaseId(), env.getName(),
                "Asked to extend by " + minutes + " minutes, beyond the " + effectiveMax(env) + "-minute maximum"
                        + (reason == null || reason.isBlank() ? "." : ": " + reason));
        String envName = env.getName();
        afterCommit.run(() -> notificationService.notifyLeaseExtensionRequested(environmentId, envName, userId,
                minutes, reason));
        return new ExtendOutcome(null, toRequestDto(request, environmentId));
    }

    /**
     * An owner approves or denies a pending request. Only the first decision wins (409 for the
     * other); an approval adds the minutes from the lease's end (or now), ignoring the owner
     * maximum but never the platform's absolute maximum.
     */
    @Transactional
    public com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO decideExtension(String requestId, boolean approve,
                                                                              String notes) {
        assertFeatureEnabled();
        com.tcgdigital.vmcontrol.model.LeaseExtensionRequest request = extensionRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Lease extension request", requestId));
        EnvironmentLease lease = leaseRepository.findById(request.getLeaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Lease", request.getLeaseId()));
        Environment env = lease.getEnvironment();
        String environmentId = env.getEnvironmentId();
        String envName = env.getName(); // read now: the decision's update clears the persistence context
        securityService.assertCanView(environmentId);
        if (!securityService.canManageEnvironmentAccess(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.UnauthorizedException(
                    "Only the environment's owners can decide lease extensions");
        }
        String reviewerId = userService.getCurrentUserId();
        Instant now = Instant.now();
        com.tcgdigital.vmcontrol.model.LeaseExtensionStatus decided = approve
                ? com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.APPROVED
                : com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.DENIED;
        if (extensionRepository.transitionFromPending(requestId, decided, reviewerId, Timestamp.from(now), notes) == 0) {
            throw new com.tcgdigital.vmcontrol.exception.ConflictException("This request was already decided");
        }
        // The modifying query cleared the persistence context: reload the lease.
        lease = leaseRepository.findById(request.getLeaseId()).orElseThrow();
        if (approve && lease.getStatus() == com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE) {
            Instant base = lease.getEndsAt().toInstant().isAfter(now) ? lease.getEndsAt().toInstant() : now;
            Instant newEnd = base.plus(Duration.ofMinutes(request.getMinutes()));
            Instant absoluteCap = lease.getStartedAt().toInstant().plus(Duration.ofMinutes(platformAbsoluteMaxMinutes));
            if (newEnd.isAfter(absoluteCap)) newEnd = absoluteCap;
            lease.setEndsAt(Timestamp.from(newEnd));
            lease.setExtendedMinutesTotal(lease.getExtendedMinutesTotal() + request.getMinutes());
            leaseRepository.save(lease);
            join(lease, request.getRequesterUserId(), now);
        }
        auditService.logEnvironmentAction(reviewerId, com.tcgdigital.vmcontrol.model.AuditAction.LEASE_EXTENSION_DECIDED,
                environmentId, envName, "lease", lease.getLeaseId(), envName,
                (approve ? "Approved" : "Denied") + " a " + request.getMinutes() + "-minute extension"
                        + (notes == null || notes.isBlank() ? "." : ": " + notes));
        String requesterId = request.getRequesterUserId();
        int minutes = request.getMinutes();
        afterCommit.run(() -> notificationService.notifyLeaseExtensionDecided(requesterId, environmentId, envName,
                approve, minutes, notes));
        com.tcgdigital.vmcontrol.model.LeaseExtensionRequest fresh = extensionRepository.findById(requestId).orElseThrow();
        return toRequestDto(fresh, environmentId);
    }

    /** Pending requests on the environment's active lease, for its owners. */
    @Transactional(readOnly = true)
    public List<com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO> pendingExtensions(String environmentId) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        if (!securityService.canManageEnvironmentAccess(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.UnauthorizedException(
                    "Only the environment's owners can see extension requests");
        }
        return leaseRepository.findActiveByEnvironmentId(environmentId)
                .map(lease -> extensionRepository.findByLeaseIdAndStatusOrderByCreatedAtAsc(lease.getLeaseId(),
                                com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING).stream()
                        .map(r -> toRequestDto(r, environmentId)).toList())
                .orElse(List.of());
    }

    // ---------------------------------------------------------------- warnings (E15-T04)

    /**
     * Warn the people keeping each lease-enabled environment running at every lease.warning-minutes
     * threshold before its end: active participants, the lock holder and anyone who started an
     * operation there during the lease, if they can still operate. Each threshold fires once per
     * user (de-duplicated per warning window, so an extension re-arms them).
     *
     * @return how many warnings were created
     */
    @Transactional
    public int processLeaseWarnings() {
        if (!featureEnabled) return 0;
        Instant now = Instant.now();
        int sent = 0;
        for (int threshold : warningMinutes.stream().sorted(java.util.Comparator.reverseOrder()).toList()) {
            for (EnvironmentLease lease : leaseRepository.findActiveEndingBetween(Timestamp.from(now),
                    Timestamp.from(now.plus(Duration.ofMinutes(threshold))))) {
                Environment env = lease.getEnvironment();
                if (!isEnabledFor(env)) continue;
                Timestamp windowStart = Timestamp.from(lease.getEndsAt().toInstant().minus(Duration.ofMinutes(threshold)));
                long minutesLeft = Math.max(1, Duration.between(now, lease.getEndsAt().toInstant()).toMinutes());
                for (String userId : warningRecipients(lease)) {
                    if (notificationService.notifyLeaseExpiring(userId, env.getEnvironmentId(), env.getName(),
                            lease.getEndsAt(), minutesLeft, windowStart)) {
                        sent++;
                    }
                }
            }
        }
        return sent;
    }

    private java.util.Set<String> warningRecipients(EnvironmentLease lease) {
        String envId = lease.getEnvironment().getEnvironmentId();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        participantRepository.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(lease.getLeaseId())
                .forEach(p -> ids.add(p.getUserId()));
        lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(envId).ifPresent(l -> ids.add(l.getLockedByUserId()));
        ids.addAll(executionRepository.findInitiatorIdsSince(envId, lease.getStartedAt()));
        java.util.Set<String> recipients = new java.util.LinkedHashSet<>();
        userRepository.findAllById(ids).forEach(user -> {
            if (Boolean.TRUE.equals(user.getIsActive()) && securityService.canOperateInEnvironment(user, envId)) {
                recipients.add(user.getUserId());
            }
        });
        return recipients;
    }

    private LeaseDTO.Permissions permissionsFor(String environmentId) {
        return new LeaseDTO.Permissions(securityService.canOperateInEnvironment(environmentId),
                securityService.canManageEnvironmentAccess(environmentId));
    }

    private com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO toRequestDto(
            com.tcgdigital.vmcontrol.model.LeaseExtensionRequest r, String environmentId) {
        String requesterName = userRepository.findById(r.getRequesterUserId()).map(LeaseService::displayName).orElse(null);
        return new com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO(r.getRequestId(), r.getLeaseId(), environmentId,
                r.getRequesterUserId(), requesterName, r.getMinutes(), r.getReason(), r.getStatus().name(),
                r.getCreatedAt(), r.getReviewedByUserId(), r.getReviewedAt(), r.getReviewNotes());
    }

    // ---------------------------------------------------------------- leases

    /** The environment's active lease, or empty. */
    @Transactional(readOnly = true)
    public Optional<EnvironmentLease> getActiveLease(String environmentId) {
        return leaseRepository.findActiveByEnvironmentId(environmentId);
    }

    /** The active lease as the caller may see it; empty when there is none (204). */
    @Transactional(readOnly = true)
    public Optional<LeaseDTO> getActiveLeaseDto(String environmentId) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        return leaseRepository.findActiveByEnvironmentId(environmentId).map(lease -> toDto(lease,
                permissionsFor(environmentId)));
    }

    /**
     * The running environments the user is keeping alive (E15-T07): active leases where they are
     * still a participant — once they leave (lock released, access ended) the lease is not listed.
     */
    @Transactional(readOnly = true)
    public List<LeaseDTO> getMyLeases(String userId) {
        assertFeatureEnabled();
        User user = userRepository.findById(userId).orElse(null);
        return leaseRepository.findActiveForParticipant(userId).stream()
                .map(lease -> {
                    String envId = lease.getEnvironment().getEnvironmentId();
                    boolean canExtend = user != null && securityService.canOperateInEnvironment(user, envId);
                    LeaseDTO dto = toDto(lease, new LeaseDTO.Permissions(canExtend, false));
                    Environment env = lease.getEnvironment();
                    dto.setEnvironmentName(env.getDisplayName() != null && !env.getDisplayName().isBlank()
                            ? env.getDisplayName() : env.getName());
                    return dto;
                })
                .toList();
    }

    LeaseDTO toDto(EnvironmentLease lease, LeaseDTO.Permissions permissions) {
        List<EnvironmentLeaseParticipant> participants =
                participantRepository.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(lease.getLeaseId());
        List<String> ids = new java.util.ArrayList<>(participants.stream().map(EnvironmentLeaseParticipant::getUserId).toList());
        ids.add(lease.getStartedByUserId());
        Map<String, User> users = new HashMap<>(userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a)));

        LeaseDTO dto = new LeaseDTO();
        dto.setLeaseId(lease.getLeaseId());
        dto.setEnvironmentId(lease.getEnvironment().getEnvironmentId());
        dto.setStatus(lease.getStatus().name());
        dto.setSource(lease.getSource().name());
        dto.setStartedByUserId(lease.getStartedByUserId());
        dto.setStartedByName(displayName(users.get(lease.getStartedByUserId())));
        dto.setStartedAt(lease.getStartedAt());
        dto.setEndsAt(lease.getEndsAt());
        dto.setExtendedMinutesTotal(lease.getExtendedMinutesTotal());
        dto.setParticipants(participants.stream()
                .map(p -> new LeaseDTO.Participant(p.getUserId(), displayName(users.get(p.getUserId())), p.getJoinedAt()))
                .toList());
        dto.setPermissions(permissions);
        if (extensionRepository != null) {
            extensionRepository.findFirstByLeaseIdAndStatus(lease.getLeaseId(),
                            com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING)
                    .ifPresent(r -> dto.setPendingExtension(toRequestDto(r, dto.getEnvironmentId())));
        }
        return dto;
    }

    private static String displayName(User user) {
        if (user == null) return null;
        return user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getEmail();
    }

    private Environment loadEnvironment(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }
}
