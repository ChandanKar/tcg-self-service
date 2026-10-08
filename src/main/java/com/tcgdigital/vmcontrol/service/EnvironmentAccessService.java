package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AccessGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.dto.UpdateAccessGrantDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for Environment Access management operations.
 * Handles access requests, approvals, grants, and revocations.
 */
@Service
public class EnvironmentAccessService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentAccessService.class);

    @Value("${access.expiry.warning-days:1}")
    private int expiryWarningDays;

    @Value("${access.group-scope.enabled:true}")
    private boolean groupScopeEnabled;

    /**
     * A user may request an extension of an active grant once it expires within this many
     * days. Outside the window, a request for a scope they already hold is rejected.
     */
    @Value("${access.extension.window-days:7}")
    private int extensionWindowDays;

    @Value("${access.request.max-duration-days:180}")
    private int requestMaxDurationDays = 180;

    @Value("${access.grant.max-duration-days:365}")
    private int grantMaxDurationDays = 365;

    private final EnvironmentAccessRepository accessRepository;
    private final EnvironmentAccessRequestRepository requestRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final VmGroupRepository vmGroupRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final AutomationRuleService automationRuleService;
    private final AccessExpiryProcessor accessExpiryProcessor;
    private final AfterCommit afterCommit;

    public EnvironmentAccessService(EnvironmentAccessRepository accessRepository,
                                     EnvironmentAccessRequestRepository requestRepository,
                                     EnvironmentRepository environmentRepository,
                                     UserRepository userRepository,
                                     VmGroupRepository vmGroupRepository,
                                     AuditService auditService,
                                     NotificationService notificationService,
                                     UserService userService,
                                     AutomationRuleService automationRuleService,
                                     AccessExpiryProcessor accessExpiryProcessor,
                                     AfterCommit afterCommit) {
        this.accessRepository = accessRepository;
        this.requestRepository = requestRepository;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userService = userService;
        this.automationRuleService = automationRuleService;
        this.accessExpiryProcessor = accessExpiryProcessor;
        this.afterCommit = afterCommit;
    }

    public boolean isGroupScopeEnabled() {
        return groupScopeEnabled;
    }

    public int getExtensionWindowDays() {
        return extensionWindowDays;
    }

    public int getRequestMaxDurationDays() {
        return requestMaxDurationDays;
    }

    // ============= Access Request Operations =============

    /**
     * Create a new access request, for the whole environment or for one group within it.
     */
    @Transactional
    public EnvironmentAccessRequest createAccessRequest(String environmentId, String requesterId,
                                                         CreateAccessRequestDTO dto) {
        Environment environment = getEnvironment(environmentId);
        User requester = getUser(requesterId);

        AccessScopeType scopeType = dto.getScopeType() != null ? dto.getScopeType() : AccessScopeType.ENVIRONMENT;
        String scopeId = environmentId;
        if (scopeType == AccessScopeType.GROUP) {
            assertGroupScopeEnabled();
            if (dto.getGroupId() == null || dto.getGroupId().isBlank()) {
                throw new ValidationException("groupId is required for a GROUP-scoped request");
            }
            VmGroup group = vmGroupRepository.findById(dto.getGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException("Group", dto.getGroupId()));
            if (!group.getEnvironment().getEnvironmentId().equals(environmentId)) {
                throw new ValidationException("Group " + dto.getGroupId() + " is not in environment " + environmentId);
            }
            scopeId = dto.getGroupId();
        }
        String scopeLabel = scopeType == AccessScopeType.GROUP ? "group" : "environment";

        Timestamp now = new Timestamp(System.currentTimeMillis());
        Optional<EnvironmentAccess> active = accessRepository.findActiveByUserAndScope(requesterId, scopeType, scopeId, now);
        if (active.isPresent()) {
            // An extension: allowed only for a grant about to expire, and it must say for how
            // long — approval re-applies the grant, and without a duration the expiry would
            // stay where it is.
            if (!isExtendable(active.get(), now)) {
                throw new ValidationException("You already have active access to this " + scopeLabel);
            }
            if (dto.getDurationDays() == null) {
                throw new ValidationException("Choose how many days to extend your access by");
            }
        }
        if (dto.getDurationDays() != null && dto.getDurationDays() > requestMaxDurationDays) {
            throw new ValidationException("You can request at most " + requestMaxDurationDays + " days");
        }
        if (requestRepository.hasPendingRequestForScope(requesterId, scopeType, scopeId)) {
            throw new ValidationException("You already have a pending access request for this " + scopeLabel);
        }

        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                environment,
                requester,
                dto.getAccessLevel(),
                dto.getBusinessJustification(),
                dto.getDurationDays()
        );
        request.setScopeType(scopeType);
        request.setScopeId(scopeId);

        EnvironmentAccessRequest saved = requestRepository.save(request);
        log.info("Access request created: {} for environment {} by user {}",
                saved.getRequestId(), environmentId, requesterId);

        auditService.logAccessRequested(requesterId, environmentId, environment.getName(),
                dto.getAccessLevel().getValue());

        String environmentName = environment.getName();
        String requestId = saved.getRequestId();
        String level = dto.getAccessLevel().getValue();
        sideEffectAfterCommit("notify access request reviewers", requestId, () ->
                notificationService.notifyAccessRequestedForReviewers(
                        environmentId, environmentName, requesterId, requestId, level));

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
        EnvironmentAccessRequest request = decidePending(requestId, AccessRequestStatus.APPROVED,
                reviewerUserId, notes);
        User reviewer = getUser(reviewerUserId);

        // Reviewer-specified duration overrides what the requester asked for
        Integer effectiveDays = reviewerDurationDays != null ? reviewerDurationDays : request.getDurationDays();
        GrantOutcome outcome = applyGrant(GrantSpec.fromApprovedRequest(request, reviewer, effectiveDays, notes));

        log.info("Access request {} approved by {} for user {} on environment {}",
                requestId, reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId());

        String requesterId = request.getRequester().getUserId();
        String environmentName = request.getEnvironment().getName();
        String environmentId = request.getEnvironment().getEnvironmentId();
        sideEffectAfterCommit("notify access request approved", requestId, () ->
                notificationService.notifyAccessRequestApproved(requesterId, environmentName, environmentId));

        if (outcome.created() || outcome.levelChanged()) {
            fireAccessGrantedAutomation(request.getEnvironment().getEnvironmentId());
        }
        return outcome.access();
    }

    /**
     * Deny an access request.
     */
    @Transactional
    public EnvironmentAccessRequest denyRequest(String requestId, String reviewerUserId, String reason) {
        EnvironmentAccessRequest request = decidePending(requestId, AccessRequestStatus.DENIED,
                reviewerUserId, reason);

        log.info("Access request {} denied by {} for user {} on environment {}",
                requestId, reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId());

        auditService.logAccessDenied(reviewerUserId, request.getRequester().getUserId(),
                request.getEnvironment().getEnvironmentId(), request.getEnvironment().getName(), reason);

        String requesterId = request.getRequester().getUserId();
        String environmentName = request.getEnvironment().getName();
        String environmentId = request.getEnvironment().getEnvironmentId();
        sideEffectAfterCommit("notify access request denied", requestId, () ->
                notificationService.notifyAccessRequestDenied(requesterId, environmentName, environmentId));

        return request;
    }

    /**
     * Move a PENDING request to {@code decision} with one conditional update, so two reviewers
     * (or a reviewer and the requester cancelling) cannot both win (409 for the loser).
     *
     * @return the request as stored after the decision
     */
    private EnvironmentAccessRequest decidePending(String requestId, AccessRequestStatus decision,
                                                   String reviewerUserId, String notes) {
        EnvironmentAccessRequest request = getAccessRequest(requestId);
        User reviewer = getUser(reviewerUserId);
        if (!request.isPending()) {
            throw new ConflictException("This request was already reviewed (" + request.getStatus() + ")");
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (requestRepository.reviewIfPending(requestId, decision, reviewer, notes, now) == 0) {
            throw new ConflictException("This request was already reviewed");
        }
        return getAccessRequest(requestId);
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

        if (requestRepository.cancelIfPending(requestId, new Timestamp(System.currentTimeMillis())) == 0) {
            throw new ConflictException("This request was reviewed before it could be cancelled");
        }
        EnvironmentAccessRequest saved = getAccessRequest(requestId);

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

        notifyGrantOutcome(outcome, targetUser.getUserId(), environment.getName(), environmentId);
        if (outcome.created() || outcome.levelChanged()) {
            fireAccessGrantedAutomation(environmentId);
        }
        return outcome.access();
    }

    /**
     * Grant access directly to the environment or to specific groups within it, in one call.
     * One {@code resource_access} row is upserted per scope; access-granted automation fires
     * once for the environment.
     */
    @Transactional
    public List<EnvironmentAccess> grantScoped(String actorUserId, AccessGrantRequestDTO dto) {
        Environment environment = getEnvironment(dto.getEnvironmentId());
        User actor = getUser(actorUserId);
        User targetUser = userService.getUserByEmail(dto.getUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User with email", dto.getUserEmail()));
        boolean clearExpiry = Boolean.TRUE.equals(dto.getClearExpiry());

        List<GrantSpec> specs = new java.util.ArrayList<>();
        if (dto.getScopeType() == AccessScopeType.GROUP) {
            assertGroupScopeEnabled();
            List<String> groupIds = dto.getGroupIds();
            if (groupIds == null || groupIds.isEmpty()) {
                throw new ValidationException("At least one group id is required for GROUP scope");
            }
            for (String groupId : groupIds.stream().distinct().toList()) {
                VmGroup group = vmGroupRepository.findById(groupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
                if (!group.getEnvironment().getEnvironmentId().equals(environment.getEnvironmentId())) {
                    throw new ValidationException("Group " + groupId + " is not in environment "
                            + environment.getEnvironmentId());
                }
                specs.add(GrantSpec.directGroup(environment, group, targetUser, actor,
                        dto.getAccessLevel(), dto.getDurationDays(), clearExpiry, dto.getNotes()));
            }
        } else {
            specs.add(GrantSpec.directEnv(environment, targetUser, actor, dto.getAccessLevel(),
                    dto.getDurationDays(), clearExpiry, dto.getNotes()));
        }

        List<EnvironmentAccess> results = new java.util.ArrayList<>();
        boolean anyChange = false;
        for (GrantSpec spec : specs) {
            GrantOutcome outcome = applyGrant(spec);
            notifyGrantOutcome(outcome, targetUser.getUserId(), environment.getName(), environment.getEnvironmentId());
            anyChange |= outcome.created() || outcome.levelChanged();
            results.add(outcome.access());
        }
        if (anyChange) {
            fireAccessGrantedAutomation(environment.getEnvironmentId());
        }
        return results;
    }

    /**
     * Change an existing grant's level and/or expiry in place. The grant keeps its scope and
     * initiation. Authorization is the caller's responsibility (done in the controller).
     */
    @Transactional
    public EnvironmentAccess updateGrant(String actorUserId, String accessId, UpdateAccessGrantDTO dto) {
        EnvironmentAccess existing = getGrantById(accessId);
        if (existing.getStatus() != AccessStatus.ACTIVE) {
            throw new ValidationException("Grant is not active");
        }
        User actor = getUser(actorUserId);
        Environment environment = existing.getEnvironment();
        AccessLevel level = dto.getAccessLevel() != null ? dto.getAccessLevel() : existing.getAccessLevel();
        String notes = dto.getNotes() != null ? dto.getNotes() : existing.getNotes();

        GrantSpec spec = GrantSpec.forExisting(existing, actor, level, dto.getDurationDays(),
                Boolean.TRUE.equals(dto.getClearExpiry()), notes);
        GrantOutcome outcome = applyGrant(spec);

        notifyGrantOutcome(outcome, existing.getUser().getUserId(), environment.getName(),
                environment.getEnvironmentId());
        if (outcome.levelChanged()) {
            fireAccessGrantedAutomation(environment.getEnvironmentId());
        }
        return outcome.access();
    }

    /**
     * Revoke a single grant by its id — works for any scope (env or group).
     */
    @Transactional
    public void revokeGrantById(String actorUserId, String accessId) {
        EnvironmentAccess access = getGrantById(accessId);
        if (access.getStatus() != AccessStatus.ACTIVE) {
            throw new ValidationException("Grant is not active");
        }
        Environment environment = access.getEnvironment();
        String targetUserId = access.getUser().getUserId();

        access.revoke();
        accessRepository.save(access);

        log.info("Grant {} ({}:{}) revoked for user {} by {}", accessId, access.getScopeType(),
                access.getScopeId(), targetUserId, actorUserId);

        auditService.logAccessRevoked(actorUserId, targetUserId,
                environment.getEnvironmentId(), environment.getName());
        String environmentName = environment.getName();
        String environmentId = environment.getEnvironmentId();
        sideEffectAfterCommit("notify access revoked", accessId, () ->
                notificationService.notifyAccessRevoked(targetUserId, environmentName, environmentId));
    }

    /**
     * A grant by id, or 404.
     */
    public EnvironmentAccess getGrantById(String accessId) {
        return accessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException("AccessGrant", accessId));
    }

    private void assertGroupScopeEnabled() {
        if (!groupScopeEnabled) {
            throw new ResourceNotFoundException("Group-scoped access", "feature disabled");
        }
    }

    /** Human label for a grant's scope — the environment name, or {@code group X (env)}. */
    private String scopeLabel(EnvironmentAccess access) {
        return scopeLabel(access, vmGroupRepository);
    }

    /** "Env name", or "group G (Env name)" for a GROUP grant; shared with AccessExpiryProcessor. */
    static String scopeLabel(EnvironmentAccess access, VmGroupRepository vmGroupRepository) {
        if (access.getScopeType() == AccessScopeType.GROUP) {
            String groupName = vmGroupRepository.findById(access.getScopeId())
                    .map(VmGroup::getDisplayName).orElse(access.getScopeId());
            return "group " + groupName + " (" + access.getEnvironment().getName() + ")";
        }
        return access.getEnvironment().getName();
    }

    private void notifyGrantOutcome(GrantOutcome outcome, String targetUserId,
                                    String environmentName, String environmentId) {
        if (outcome.created()) {
            sideEffectAfterCommit("notify access granted", targetUserId, () ->
                    notificationService.notifyAccessGranted(targetUserId, environmentName, environmentId));
        } else if (outcome.levelChanged()) {
            AccessLevel previousLevel = outcome.previousLevel();
            AccessLevel newLevel = outcome.access().getAccessLevel();
            sideEffectAfterCommit("notify access level changed", targetUserId, () ->
                    notificationService.notifyAccessLevelChanged(targetUserId, environmentName, environmentId,
                            previousLevel, newLevel));
        }
    }

    private void fireAccessGrantedAutomation(String environmentId) {
        sideEffectAfterCommit("trigger access-granted automation rules", environmentId, () ->
                automationRuleService.handleAccessGranted(environmentId));
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

        log.info("Access {} for user {} on {} scope {}:{} by {}",
                created ? "granted" : (levelChanged ? "level-changed" : "refreshed"),
                targetUserId, environmentId, spec.scopeType, spec.scopeId, actorId);

        return new GrantOutcome(saved, created, previousLevel);
    }

    private void applyExpiry(EnvironmentAccess access, GrantSpec spec) {
        // Every grant path (direct, scoped, update, approval, onboarding) ends here.
        if (spec.durationDays != null && spec.durationDays > grantMaxDurationDays) {
            throw new ValidationException("Access can be granted for at most " + grantMaxDurationDays + " days");
        }
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

        static GrantSpec directGroup(Environment environment, VmGroup group, User targetUser, User actor,
                                     AccessLevel level, Integer durationDays, boolean clearExpiry, String notes) {
            return new GrantSpec(environment, targetUser, actor, level, AccessScopeType.GROUP,
                    group.getGroupId(), AccessInitiation.DIRECT, null, durationDays, clearExpiry, notes);
        }

        static GrantSpec fromApprovedRequest(EnvironmentAccessRequest request, User reviewer,
                                             Integer durationDays, String notes) {
            return new GrantSpec(request.getEnvironment(), request.getRequester(), reviewer,
                    request.getRequestedAccessLevel(), request.getScopeType(), request.getScopeId(),
                    AccessInitiation.REQUEST, request.getRequestId(), durationDays, false, notes);
        }

        /** Re-apply an existing grant with a possibly-changed level / expiry, keeping its scope and origin. */
        static GrantSpec forExisting(EnvironmentAccess existing, User actor, AccessLevel level,
                                     Integer durationDays, boolean clearExpiry, String notes) {
            return new GrantSpec(existing.getEnvironment(), existing.getUser(), actor, level,
                    existing.getScopeType(), existing.getScopeId(), existing.getInitiation(),
                    existing.getSourceRequestId(), durationDays, clearExpiry, notes);
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

        String environmentName = environment.getName();
        sideEffectAfterCommit("notify access revoked", access.getAccessId(), () ->
                notificationService.notifyAccessRevoked(userId, environmentName, environmentId));
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
     * Grants of a user that ended (expired or revoked) in the last {@code days} days, newest
     * first. A scope the user has since been granted again is left out — they hold it now.
     */
    public List<EnvironmentAccess> getEndedAccessForUser(String userId, int days) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(days));
        Set<String> heldScopes = accessRepository.findActiveAccessByUser(userId, now).stream()
                .map(a -> a.getScopeType() + ":" + a.getScopeId())
                .collect(Collectors.toSet());
        return accessRepository.findEndedAccessByUserSince(userId, now, since).stream()
                .filter(a -> !heldScopes.contains(a.getScopeType() + ":" + a.getScopeId()))
                .sorted(Comparator.comparing(EnvironmentAccessService::endedAt).reversed())
                .toList();
    }

    /** When a grant ended: its revocation if revoked, else its expiry. */
    public static Timestamp endedAt(EnvironmentAccess access) {
        return access.getStatus() == AccessStatus.REVOKED && access.getRevokedAt() != null
                ? access.getRevokedAt()
                : access.getExpiresAt();
    }

    /** True when an active grant expires inside the extension window. */
    public boolean isExtendable(EnvironmentAccess access, Timestamp now) {
        Timestamp expiresAt = access.getExpiresAt();
        if (expiresAt == null) {
            return false;
        }
        long windowMillis = TimeUnit.DAYS.toMillis(extensionWindowDays);
        return expiresAt.getTime() - now.getTime() <= windowMillis;
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

    /**
     * The user's active grant on one specific scope (ENVIRONMENT or GROUP), or empty.
     * Unlike {@link #getAccess}, this can return a GROUP-scoped grant.
     */
    public Optional<EnvironmentAccess> getActiveGrant(String userId, AccessScopeType scopeType, String scopeId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return accessRepository.findActiveByUserAndScope(userId, scopeType, scopeId, now);
    }

    /**
     * The user's active GROUP-scoped access level for each group id they hold a grant on —
     * {@code groupId -> level}. Groups with no grant are absent from the map.
     */
    public Map<String, AccessLevel> getActiveGroupGrantLevels(String userId, Collection<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, AccessLevel> byGroup = new HashMap<>();
        for (EnvironmentAccess ea : accessRepository.findActiveGroupGrantsForUser(userId, groupIds, now)) {
            byGroup.merge(ea.getScopeId(), ea.getAccessLevel(),
                    (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        }
        return byGroup;
    }

    // ============= Expiration Handling =============

    /**
     * Expire past-due grants, one transaction per grant: a failing row is logged and retried on
     * the next run instead of rolling back the whole batch (C4). Called by a scheduled job.
     *
     * @return the number of grants actually expired
     */
    public int processExpiredAccess() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int expired = 0;
        for (String accessId : accessRepository.findExpiredAccessIds(now)) {
            try {
                if (accessExpiryProcessor.expireOne(accessId)) {
                    expired++;
                }
            } catch (Exception e) {
                log.warn("Could not expire access {}; will retry on the next run: {}", accessId, e.getMessage());
            }
        }
        return expired;
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
            String userId = access.getUser().getUserId();
            String label = scopeLabel(access);
            String environmentId = access.getEnvironment().getEnvironmentId();
            String accessId = access.getAccessId();
            Timestamp expiresAt = access.getExpiresAt();
            sideEffectAfterCommit("notify access expiring", accessId, () ->
                    notificationService.notifyAccessExpiring(userId, label, environmentId, accessId, expiresAt));
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

    /**
     * Run a side effect (notification, automation) only once the transaction has committed, in a
     * transaction of its own: a failure there can neither roll back nor mark rollback-only the
     * grant, revoke or review that triggered it.
     */
    private void sideEffectAfterCommit(String action, String entityId, Runnable runnable) {
        afterCommit.run(() -> runNotificationSideEffect(action, entityId, runnable));
    }

    private void runNotificationSideEffect(String action, String entityId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for {}: {}", action, entityId, e.getMessage());
        }
    }
}


