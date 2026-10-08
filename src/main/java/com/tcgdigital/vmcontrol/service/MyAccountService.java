package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.MyActivityItemDTO;
import com.tcgdigital.vmcontrol.dto.MyProfileDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.AccessInitiation;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only views of the signed-in user's own account for the My Account panel: profile
 * details and a combined feed of their VM operations and access history.
 */
@Service
@Transactional(readOnly = true)
public class MyAccountService {

    /** How far back the activity feed looks for expired / revoked grants. */
    private static final int ENDED_ACCESS_LOOKBACK_DAYS = 90;

    @Value("${entraid.enabled:false}")
    private boolean entraIdEnabled;

    private final UserRepository userRepository;
    private final EnvironmentRepository environmentRepository;
    private final EnvironmentAccessRepository accessRepository;
    private final EnvironmentAccessRequestRepository requestRepository;
    private final OperationExecutionRepository executionRepository;
    private final VmGroupRepository vmGroupRepository;
    private final EnvironmentAccessService accessService;

    public MyAccountService(UserRepository userRepository,
                            EnvironmentRepository environmentRepository,
                            EnvironmentAccessRepository accessRepository,
                            EnvironmentAccessRequestRepository requestRepository,
                            OperationExecutionRepository executionRepository,
                            VmGroupRepository vmGroupRepository,
                            EnvironmentAccessService accessService) {
        this.userRepository = userRepository;
        this.environmentRepository = environmentRepository;
        this.accessRepository = accessRepository;
        this.requestRepository = requestRepository;
        this.executionRepository = executionRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.accessService = accessService;
    }

    public MyProfileDTO getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        String onboardedByName = user.getOnboardedBy() == null ? null
                : userRepository.findById(user.getOnboardedBy()).map(MyAccountService::nameOf).orElse(null);

        List<MyProfileDTO.EnvironmentRef> administered = environmentRepository
                .findAllById(accessService.getAdministeredEnvironmentIds(userId)).stream()
                .map(env -> new MyProfileDTO.EnvironmentRef(env.getEnvironmentId(), envName(env)))
                .sorted(Comparator.comparing(MyProfileDTO.EnvironmentRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new MyProfileDTO(
                user.getUserId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCompanyName(),
                user.isAdmin(),
                user.isEnvAdmin(),
                entraIdEnabled ? "ENTRA_ID" : "LOCAL",
                user.getLastLoginAt(),
                user.getPreviousLoginAt(),
                user.getCreatedAt(),
                user.getOnboardedAt(),
                onboardedByName,
                administered,
                accessService.getExtensionWindowDays(),
                accessService.getRequestMaxDurationDays());
    }

    /**
     * The user's recent activity, newest first: operations they started, access requests they
     * made and how each was decided, grants given to them directly, and grants that ended.
     */
    public List<MyActivityItemDTO> getActivity(String userId, int limit) {
        PageRequest top = PageRequest.of(0, limit);
        List<MyActivityItemDTO> items = new ArrayList<>();

        for (OperationExecution op : executionRepository.findRecentByInitiator(userId, top)) {
            Environment env = op.getEnvironment();
            items.add(new MyActivityItemDTO("OPERATION", op.getOperationType().name(), op.getStatus().name(),
                    env.getEnvironmentId(), envName(env), null, null, null,
                    op.getTotalTargets(), op.getCompletedTargets(), op.getFailedTargets(),
                    op.getErrorMessage(), op.getStartedAt(), op.getExecutionId()));
        }

        List<EnvironmentAccessRequest> requests = requestRepository.findByRequester_UserIdOrderByCreatedAtDesc(userId)
                .stream().limit(limit).toList();
        List<EnvironmentAccess> grants = accessRepository.findRecentGrantsByUser(userId, top);
        List<EnvironmentAccess> edited = accessRepository.findRecentlyModifiedGrantsByUser(userId, top);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(ENDED_ACCESS_LOOKBACK_DAYS));
        List<EnvironmentAccess> ended = accessRepository.findEndedAccessByUserSince(userId, now, since);
        Map<String, String> groupNames = groupNames(requests, grants, ended, edited);

        for (EnvironmentAccessRequest r : requests) {
            String scope = r.getScopeType() == AccessScopeType.GROUP ? groupNames.get(r.getScopeId()) : null;
            items.add(accessItem("REQUESTED", r.getEnvironment(), scope, r, null, null, r.getCreatedAt()));
            AccessRequestStatus status = r.getStatus();
            if (status == AccessRequestStatus.APPROVED || status == AccessRequestStatus.DENIED) {
                // An approved extension moved the expiry of an existing grant (LOW-ACC-4).
                String event = status == AccessRequestStatus.APPROVED && r.isExtension() ? "EXTENDED" : status.name();
                items.add(accessItem(event, r.getEnvironment(), scope, r,
                        r.getReviewedBy() != null ? nameOf(r.getReviewedBy()) : null,
                        r.getReviewDecisionNotes(),
                        r.getReviewedAt() != null ? r.getReviewedAt() : r.getUpdatedAt()));
            } else if (status == AccessRequestStatus.CANCELLED) {
                items.add(accessItem("CANCELLED", r.getEnvironment(), scope, r, null, null, r.getUpdatedAt()));
            }
        }

        // Grants that came from a request already show as APPROVED above.
        for (EnvironmentAccess g : grants) {
            if (g.getInitiation() == AccessInitiation.DIRECT) {
                items.add(grantItem("GRANTED", g, groupNames, nameOf(g.getGrantedBy()), g.getGrantedAt()));
            }
        }
        for (EnvironmentAccess g : edited) {
            items.add(grantItem("UPDATED", g, groupNames, nameOf(g.getLastModifiedBy()), g.getLastModifiedAt()));
        }
        for (EnvironmentAccess g : ended) {
            String event = g.getStatus() == AccessStatus.REVOKED ? "REVOKED" : "EXPIRED";
            items.add(grantItem(event, g, groupNames, null, EnvironmentAccessService.endedAt(g)));
        }

        return items.stream()
                .filter(i -> i.occurredAt() != null)
                .sorted(Comparator.comparing(MyActivityItemDTO::occurredAt).reversed())
                .limit(limit)
                .toList();
    }

    private static MyActivityItemDTO accessItem(String event, Environment env, String scopeName,
                                                EnvironmentAccessRequest r, String actorName,
                                                String note, Timestamp at) {
        return new MyActivityItemDTO("ACCESS", event, null, env.getEnvironmentId(), envName(env),
                scopeName, r.getRequestedAccessLevel().name(), actorName, null, null, null,
                note, at, r.getRequestId());
    }

    private static MyActivityItemDTO grantItem(String event, EnvironmentAccess g, Map<String, String> groupNames,
                                               String actorName, Timestamp at) {
        Environment env = g.getEnvironment();
        String scope = g.getScopeType() == AccessScopeType.GROUP ? groupNames.get(g.getScopeId()) : null;
        return new MyActivityItemDTO("ACCESS", event, null, env.getEnvironmentId(), envName(env),
                scope, g.getAccessLevel().name(), actorName, null, null, null,
                null, at, g.getAccessId());
    }

    /** Resolve every GROUP scope id in the feed to its display name, in one query. */
    private Map<String, String> groupNames(List<EnvironmentAccessRequest> requests,
                                           List<EnvironmentAccess> grants,
                                           List<EnvironmentAccess> ended,
                                           List<EnvironmentAccess> edited) {
        List<String> ids = new ArrayList<>();
        requests.stream().filter(r -> r.getScopeType() == AccessScopeType.GROUP)
                .forEach(r -> ids.add(r.getScopeId()));
        grants.stream().filter(g -> g.getScopeType() == AccessScopeType.GROUP)
                .forEach(g -> ids.add(g.getScopeId()));
        ended.stream().filter(g -> g.getScopeType() == AccessScopeType.GROUP)
                .forEach(g -> ids.add(g.getScopeId()));
        edited.stream().filter(g -> g.getScopeType() == AccessScopeType.GROUP)
                .forEach(g -> ids.add(g.getScopeId()));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return vmGroupRepository.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(VmGroup::getGroupId,
                        g -> g.getDisplayName() != null ? g.getDisplayName() : g.getName()));
    }

    private static String envName(Environment env) {
        return env.getDisplayName() != null ? env.getDisplayName() : env.getName();
    }

    private static String nameOf(User user) {
        if (user == null) {
            return null;
        }
        String name = user.getDisplayName();
        return name != null && !name.isBlank() ? name : user.getEmail();
    }
}
