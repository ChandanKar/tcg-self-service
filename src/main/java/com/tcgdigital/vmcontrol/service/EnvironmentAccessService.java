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

    public EnvironmentAccessService(EnvironmentAccessRepository accessRepository,
                                     EnvironmentAccessRequestRepository requestRepository,
                                     EnvironmentRepository environmentRepository,
                                     UserRepository userRepository,
                                     AuditService auditService,
                                     NotificationService notificationService,
                                     UserService userService) {
        this.accessRepository = accessRepository;
        this.requestRepository = requestRepository;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userService = userService;
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
     * Approve an access request.
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

        // Create the access grant
        EnvironmentAccess access = EnvironmentAccess.create(
                request.getEnvironment(),
                request.getRequester(),
                request.getRequestedAccessLevel(),
                reviewer
        );

        // Reviewer-specified duration overrides what the requester asked for
        Integer effectiveDays = reviewerDurationDays != null ? reviewerDurationDays : request.getDurationDays();
        if (effectiveDays != null) {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(effectiveDays);
            access.setExpiresAt(Timestamp.valueOf(expiresAt));
        }

        access.setNotes(notes);
        EnvironmentAccess saved = accessRepository.save(access);

        log.info("Access request {} approved by {} for user {} on environment {}",
                requestId, reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId());

        auditService.logAccessGranted(reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId(), request.getEnvironment().getName(),
                request.getRequestedAccessLevel().getValue());

        notificationService.notifyAccessRequestApproved(
                request.getRequester().getUserId(),
                request.getEnvironment().getName(),
                request.getEnvironment().getEnvironmentId());

        return saved;
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
     * Grant access directly (admin operation, bypasses request workflow).
     */
    @Transactional
    public EnvironmentAccess grantAccess(String environmentId, String grantedByUserId, GrantAccessDTO dto) {
        Environment environment = getEnvironment(environmentId);
        User grantedBy = getUser(grantedByUserId);
        User targetUser = userService.getUserByEmail(dto.getUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User with email", dto.getUserEmail()));

        String targetUserId = targetUser.getUserId();

        // Check if user already has active access
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Optional<EnvironmentAccess> existingAccess = accessRepository.findActiveAccess(
                environmentId, targetUserId, now);

        if (existingAccess.isPresent()) {
            // Update existing access level instead of creating new
            EnvironmentAccess access = existingAccess.get();
            access.setAccessLevel(dto.getAccessLevel());
            access.setNotes(dto.getNotes());
            if (dto.getDurationDays() != null) {
                LocalDateTime expiresAt = LocalDateTime.now().plusDays(dto.getDurationDays());
                access.setExpiresAt(Timestamp.valueOf(expiresAt));
            }
            EnvironmentAccess saved = accessRepository.save(access);
            log.info("Access updated for user {} on environment {} by {}",
                    targetUserId, environmentId, grantedByUserId);
            return saved;
        }

        // Create new access
        EnvironmentAccess access = EnvironmentAccess.create(environment, targetUser, dto.getAccessLevel(), grantedBy);

        if (dto.getDurationDays() != null) {
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(dto.getDurationDays());
            access.setExpiresAt(Timestamp.valueOf(expiresAt));
        }

        access.setNotes(dto.getNotes());
        EnvironmentAccess saved = accessRepository.save(access);

        log.info("Access granted to user {} on environment {} by {}",
                targetUserId, environmentId, grantedByUserId);

        auditService.logAccessGranted(grantedByUserId, targetUserId, environmentId,
                environment.getName(), dto.getAccessLevel().getValue());

        notificationService.notifyAccessGranted(targetUserId, environment.getName(), environmentId);

        return saved;
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


