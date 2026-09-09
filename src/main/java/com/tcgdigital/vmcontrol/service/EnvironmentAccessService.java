package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for Environment Access management operations.
 * Handles access requests, approvals, grants, and revocations.
 */
@Service
public class EnvironmentAccessService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentAccessService.class);

    @Value("${access.expiry.warning-days:1}")
    private int expiryWarningDays;

    private final EnvironmentAccessRepository accessRepository;
    private final EnvironmentAccessRequestRepository requestRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final AutomationRuleService automationRuleService;

    public EnvironmentAccessService(EnvironmentAccessRepository accessRepository,
                                     EnvironmentAccessRequestRepository requestRepository,
                                     EnvironmentRepository environmentRepository,
                                     UserRepository userRepository,
                                     AuditService auditService,
                                     NotificationService notificationService,
                                     UserService userService,
                                     AutomationRuleService automationRuleService) {
        this.accessRepository = accessRepository;
        this.requestRepository = requestRepository;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userService = userService;
        this.automationRuleService = automationRuleService;
    }

    // ============= Access Request Operations =============

    /**
     * Create a new access request.
     */
    @Transactional
    public EnvironmentAccessRequest createAccessRequest(String environmentId, String requesterId,
                                                         CreateAccessRequestDTO dto) {
        Environment environment = getEnvironment(environmentId);
        User requester = getUser(requesterId);

        // Check if user already has active access
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Optional<EnvironmentAccess> existingAccess = accessRepository.findActiveAccess(environmentId, requesterId, now);
        if (existingAccess.isPresent()) {
            throw new ValidationException("You already have active access to this environment");
        }

        // Check if user already has a pending request
        if (requestRepository.hasPendingRequest(environmentId, requesterId)) {
            throw new ValidationException("You already have a pending access request for this environment");
        }

        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                environment,
                requester,
                dto.getAccessLevel(),
                dto.getBusinessJustification(),
                dto.getDurationDays()
        );

        EnvironmentAccessRequest saved = requestRepository.save(request);
        log.info("Access request created: {} for environment {} by user {}",
                saved.getRequestId(), environmentId, requesterId);

        auditService.logAccessRequested(requesterId, environmentId, environment.getName(),
                dto.getAccessLevel().getValue());

        runNotificationSideEffect("notify access request reviewers", saved.getRequestId(), () ->
                notificationService.notifyAccessRequestedForReviewers(
                        environmentId,
                        environment.getName(),
                        requesterId,
                        saved.getRequestId(),
                        dto.getAccessLevel().getValue()));

        return saved;
    }

    /**
     * Get access request by ID.
     */
    public EnvironmentAccessRequest getAccessRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("AccessRequest", requestId));
    }

    /**
     * Get all pending access requests for reviewers (admins/env_admins).
     */
    public List<EnvironmentAccessRequest> getPendingRequests() {
        return requestRepository.findByStatusOrderByCreatedAtAsc(AccessRequestStatus.PENDING);
    }

    /**
     * Get pending requests that a specific user can review.
     */
    public List<EnvironmentAccessRequest> getPendingRequestsForReviewer(String reviewerUserId) {
        return requestRepository.findPendingRequestsForReviewer(reviewerUserId);
    }

    /**
     * Get all access requests by a user.
     */
    public List<EnvironmentAccessRequest> getRequestsByUser(String userId) {
        return requestRepository.findByRequester_UserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get recent requests for an environment.
     */
    public List<EnvironmentAccessRequest> getRequestsForEnvironment(String environmentId) {
        return requestRepository.findRecentRequestsByEnvironment(environmentId);
    }

    /**
     * Approve an access request. The grant it produces goes through the same {@link #applyGrant}
     * path as a direct admin grant (upsert + audit + automation); the requester additionally
     * gets an {@code ACCESS_REQUEST_APPROVED} notification since that is the event they were
     * waiting on.
     */
    @Transactional
    public EnvironmentAccess approveRequest(String requestId, String reviewerUserId, String notes,
                                             Integer reviewerDurationDays) {
        EnvironmentAccessRequest request = getAccessRequest(requestId);
        User reviewer = getUser(reviewerUserId);

        if (!request.isPending()) {
            throw new ValidationException("Request is not pending: current status is " + request.getStatus());
        }

        // Mark request as approved
        request.approve(reviewer, notes);
        requestRepository.save(request);

        // Reviewer-specified duration overrides what the requester asked for
        Integer effectiveDays = reviewerDurationDays != null ? reviewerDurationDays : request.getDurationDays();
        GrantOutcome outcome = applyGrant(GrantSpec.fromApprovedRequest(request, reviewer, effectiveDays, notes));

        log.info("Access request {} approved by {} for user {} on environment {}",
                requestId, reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId());

        notificationService.notifyAccessRequestApproved(
                request.getRequester().getUserId(),
                request.getEnvironment().getName(),
                request.getEnvironment().getEnvironmentId());

        return outcome.access();
    }

    /**
     * Deny an access request.
     */
    @Transactional
    public EnvironmentAccessRequest denyRequest(String requestId, String reviewerUserId, String reason) {
        EnvironmentAccessRequest request = getAccessRequest(requestId);
        User reviewer = getUser(reviewerUserId);

        if (!request.isPending()) {
            throw new ValidationException("Request is not pending: current status is " + request.getStatus());
        }

        request.deny(reviewer, reason);
        EnvironmentAccessRequest saved = requestRepository.save(request);

        log.info("Access request {} denied by {} for user {} on environment {}",
                requestId, reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId());

        auditService.logAccessDenied(reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId(), request.getEnvironment().getName(), reason);

        notificationService.notifyAccessRequestDenied(
                request.getRequester().getUserId(),
                request.getEnvironment().getName(),
                request.getEnvironment().getEnvironmentId());

        return saved;
    }

    /**
     * Cancel an access request (by the requester).
     */
    @Transactional
    public EnvironmentAccessRequest cancelRequest(String requestId, String requesterId) {
        EnvironmentAccessRequest request = getAccessRequest(requestId);

        // Verify the requester is cancelling their own request
        if (!request.getRequester().getUserId().equals(requesterId)) {
            throw new ValidationException("You can only cancel your own access requests");
        }

        if (!request.isPending()) {
            throw new ValidationException("Only pending requests can be cancelled");
        }

        request.cancel();
        EnvironmentAccessRequest saved = requestRepository.save(request);

        log.info("Access request {} cancelled by requester {}", requestId, requesterId);

        return saved;
    }

    // ============= Direct Access Grant Operations =============

    /**
     * Grant access directly (admin operation, bypasses request workflow). New grant or a
     * change to an existing one both flow through {@link #applyGrant}; the user is notified
     * ({@code ACCESS_GRANTED} on a new grant, {@code ACCESS_LEVEL_CHANGED} when the level
     * moved, nothing on a no-op refresh).
     */
    @Transactional
    public EnvironmentAccess grantAccess(String environmentId, String grantedByUserId, GrantAccessDTO dto) {
        Environment environment = getEnvironment(environmentId);
        User grantedBy = getUser(grantedByUserId);
        User targetUser = userService.getUserByEmail(dto.getUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User with email", dto.getUserEmail()));

        GrantOutcome outcome = applyGrant(GrantSpec.directEnv(
                environment, targetUser, grantedBy, dto.getAccessLevel(),
                dto.getDurationDays(), Boolean.TRUE.equals(dto.getClearExpiry()), dto.getNotes()));

        if (outcome.created()) {
            notificationService.notifyAccessGranted(targetUser.getUserId(), environment.getName(), environmentId);
        } else if (outcome.levelChanged()) {
            notificationService.notifyAccessLevelChanged(targetUser.getUserId(), environment.getName(),
                    environmentId, outcome.previousLevel(), dto.getAccessLevel());
        }

        return outcome.access();
    }

    // ============= Grant application (shared by direct grant + request approval) =============

    /**
     * The single place a grant is created or changed. Upserts the active grant for
     * {@code (user, scopeType, scopeId)}, records audit ({@code ACCESS_GRANTED} on create,
     * {@code ACCESS_LEVEL_CHANGED} when the level moved), and fires access-granted automation
     * on create or level change. Callers own the user-facing notification, since its wording
     * differs (granted vs. request approved vs. level changed).
     */
    private GrantOutcome applyGrant(GrantSpec spec) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Optional<EnvironmentAccess> existing = accessRepository.findActiveByUserAndScope(
                spec.targetUser.getUserId(), spec.scopeType, spec.scopeId, now);

        boolean created;
        AccessLevel previousLevel = null;
        EnvironmentAccess access;

        if (existing.isPresent()) {
            access = existing.get();
            previousLevel = access.getAccessLevel();
            access.setAccessLevel(spec.level);
            access.setGrantedBy(spec.actor);
            if (spec.notes != null) {
                access.setNotes(spec.notes);
            }
            applyExpiry(access, spec);
            created = false;
        } else {
            access = EnvironmentAccess.create(spec.environment, spec.targetUser, spec.level, spec.actor);
            access.setScopeType(spec.scopeType);
            access.setScopeId(spec.scopeId);
            access.setInitiation(spec.initiation);
            access.setSourceRequestId(spec.sourceRequestId);
            access.setNotes(spec.notes);
            applyExpiry(access, spec);
            created = true;
        }

        EnvironmentAccess saved = accessRepository.save(access);
        boolean levelChanged = !created && previousLevel != spec.level;

        String environmentId = spec.environment.getEnvironmentId();
        String environmentName = spec.environment.getName();
        String actorId = spec.actor.getUserId();
        String targetUserId = spec.targetUser.getUserId();

        if (created) {
            auditService.logAccessGranted(actorId, targetUserId, environmentId, environmentName,
                    spec.level.getValue());
        } else if (levelChanged) {
            auditService.logAccessLevelChanged(actorId, targetUserId, environmentId, environmentName,
                    previousLevel.getValue(), spec.level.getValue());
        }

        if (created || levelChanged) {
            runNotificationSideEffect("trigger access-granted automation rules", environmentId, () ->
                    automationRuleService.handleAccessGranted(environmentId));
        }

        log.info("Access {} for user {} on {} scope {}:{} by {}",
                created ? "granted" : (levelChanged ? "level-changed" : "refreshed"),
                targetUserId, environmentId, spec.scopeType, spec.scopeId, actorId);

        return new GrantOutcome(saved, created, previousLevel);
    }

    private void applyExpiry(EnvironmentAccess access, GrantSpec spec) {
        if (spec.durationDays != null) {
            access.setExpiresAt(Timestamp.valueOf(LocalDateTime.now().plusDays(spec.durationDays)));
        } else if (spec.clearExpiry) {
            access.setExpiresAt(null);
        }
        // else: leave the existing grant's expiry untouched (null for a brand-new grant)
    }

    /** What {@link #applyGrant} did, so the caller can pick the right user notification. */
    private record GrantOutcome(EnvironmentAccess access, boolean created, AccessLevel previousLevel) {
        boolean levelChanged() {
            return !created && previousLevel != access.getAccessLevel();
        }
    }

    /**
     * A grant to apply. Always carries the enclosing environment; {@code scopeType}/{@code
     * scopeId} say whether it lands on the whole environment or one group. Built via the
     * static factories, never directly.
     */
    private static final class GrantSpec {
        final Environment environment;
        final User targetUser;
        final User actor;
        final AccessLevel level;
        final AccessScopeType scopeType;
        final String scopeId;
        final AccessInitiation initiation;
        final String sourceRequestId;
        final Integer durationDays;
        final boolean clearExpiry;
        final String notes;

        private GrantSpec(Environment environment, User targetUser, User actor, AccessLevel level,
                          AccessScopeType scopeType, String scopeId, AccessInitiation initiation,
                          String sourceRequestId, Integer durationDays, boolean clearExpiry, String notes) {
            this.environment = environment;
            this.targetUser = targetUser;
            this.actor = actor;
            this.level = level;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.initiation = initiation;
            this.sourceRequestId = sourceRequestId;
            this.durationDays = durationDays;
            this.clearExpiry = clearExpiry;
            this.notes = notes;
        }

        static GrantSpec directEnv(Environment environment, User targetUser, User actor, AccessLevel level,
                                   Integer durationDays, boolean clearExpiry, String notes) {
            return new GrantSpec(environment, targetUser, actor, level, AccessScopeType.ENVIRONMENT,
                    environment.getEnvironmentId(), AccessInitiation.DIRECT, null, durationDays, clearExpiry, notes);
        }

        static GrantSpec fromApprovedRequest(EnvironmentAccessRequest request, User reviewer,
                                             Integer durationDays, String notes) {
            return new GrantSpec(request.getEnvironment(), request.getRequester(), reviewer,
                    request.getRequestedAccessLevel(), request.getScopeType(), request.getScopeId(),
                    AccessInitiation.REQUEST, request.getRequestId(), durationDays, false, notes);
        }
    }

    /**
     * Revoke access from a user.
     */
    @Transactional
    public void revokeAccess(String environmentId, String userId, String revokedByUserId) {
        Environment environment = getEnvironment(environmentId);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        EnvironmentAccess access = accessRepository.findActiveAccess(environmentId, userId, now)
                .orElseThrow(() -> new ValidationException("User does not have active access to this environment"));

        access.revoke();
        accessRepository.save(access);

        log.info("Access revoked for user {} on environment {} by {}", userId, environmentId, revokedByUserId);

        auditService.logAccessRevoked(revokedByUserId, userId, environmentId, environment.getName());

        notificationService.notifyAccessRevoked(userId, environment.getName(), environmentId);
    }

    // ============= Access Query Operations =============

    /**
     * Get all active access grants for an environment.
     */
    public List<EnvironmentAccess> getAccessForEnvironment(String environmentId) {
        return accessRepository.findActiveAccessByEnvironment(environmentId);
    }

    /**
     * Get all active access grants for a user.
     */
    public List<EnvironmentAccess> getAccessForUser(String userId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return accessRepository.findActiveAccessByUser(userId, now);
    }

    /**
     * Get IDs of environments where the user holds ADMIN-level access — i.e. environments
     * this env-admin actually administers, as opposed to the global {@code envAdmin} role
     * flag which says nothing about which environments they're scoped to.
     */
    public List<String> getAdministeredEnvironmentIds(String userId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return accessRepository.findByUserWithMinAccessLevel(userId, AccessLevel.ADMIN, now).stream()
                .map(access -> access.getEnvironment().getEnvironmentId())
                .distinct()
                .toList();
    }

    /**
     * Check if user has access to an environment.
     */
    public boolean hasAccess(String environmentId, String userId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return accessRepository.hasAccess(environmentId, userId, now);
    }

    /**
     * Check if user has at least the required access level.
     */
    public boolean hasAccessLevel(String environmentId, String userId, AccessLevel requiredLevel) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Optional<EnvironmentAccess> access = accessRepository.findActiveAccess(environmentId, userId, now);

        if (access.isEmpty()) {
            return false;
        }

        // Compare ordinal values - higher ordinal means higher access
        return access.get().getAccessLevel().ordinal() >= requiredLevel.ordinal();
    }

    /**
     * Get access grant for a specific user on an environment.
     */
    public Optional<EnvironmentAccess> getAccess(String environmentId, String userId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return accessRepository.findActiveAccess(environmentId, userId, now);
    }

    // ============= Expiration Handling =============

    /**
     * Process expired access grants.
     * Should be called by a scheduled job.
     */
    @Transactional
    public int processExpiredAccess() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<EnvironmentAccess> expiredAccess = accessRepository.findExpiredAccessWithDetails(now);

        for (EnvironmentAccess access : expiredAccess) {
            access.setStatus(AccessStatus.EXPIRED);
            accessRepository.save(access);
            log.info("Access expired for user {} on environment {}",
                    access.getUser().getUserId(), access.getEnvironment().getEnvironmentId());

            runNotificationSideEffect("notify access expired", access.getAccessId(), () ->
                    notificationService.notifyAccessExpired(
                            access.getUser().getUserId(),
                            access.getEnvironment().getName(),
                            access.getEnvironment().getEnvironmentId(),
                            access.getAccessId()));
        }

        return expiredAccess.size();
    }

    /**
     * Send one-time warning notifications for access grants expiring soon.
     */
    @Transactional
    public int processExpiringAccessWarnings() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp warningWindowEnd = Timestamp.valueOf(LocalDateTime.now().plusDays(expiryWarningDays));
        List<EnvironmentAccess> expiringAccess = accessRepository.findAccessExpiringBetweenWithDetails(
                now,
                warningWindowEnd);

        for (EnvironmentAccess access : expiringAccess) {
            runNotificationSideEffect("notify access expiring", access.getAccessId(), () ->
                    notificationService.notifyAccessExpiring(
                            access.getUser().getUserId(),
                            access.getEnvironment().getName(),
                            access.getEnvironment().getEnvironmentId(),
                            access.getAccessId(),
                            access.getExpiresAt()));
        }

        return expiringAccess.size();
    }

    // ============= Helper Methods =============

    private Environment getEnvironment(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }

    private User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private void runNotificationSideEffect(String action, String entityId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for {}: {}", action, entityId, e.getMessage());
        }
    }
}


