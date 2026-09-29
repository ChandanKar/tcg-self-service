package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AccessGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.dto.OnboardUserDTO;
import com.tcgdigital.vmcontrol.exception.DirectoryLookupException;
import com.tcgdigital.vmcontrol.exception.UserAlreadyExistsException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates admin user onboarding (Microsoft Graph directory lookup, or manual entry),
 * optionally with a first access grant — all in one transaction so a grant failure rolls the
 * user create back. See {@code docs/graph-user-onboarding.md}.
 *
 * <p>Kept separate from {@link UserService} so that the dependency on
 * {@link EnvironmentAccessService} (which itself depends on {@code UserService}) does not
 * create a cycle.
 */
@Service
public class UserOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(UserOnboardingService.class);

    private final UserService userService;
    private final EnvironmentAccessService environmentAccessService;
    private final GraphDirectoryService graphDirectoryService;
    private final UserRepository userRepository;

    public UserOnboardingService(UserService userService,
                                 EnvironmentAccessService environmentAccessService,
                                 GraphDirectoryService graphDirectoryService,
                                 UserRepository userRepository) {
        this.userService = userService;
        this.environmentAccessService = environmentAccessService;
        this.graphDirectoryService = graphDirectoryService;
        this.userRepository = userRepository;
    }

    public record OnboardResult(User user, List<EnvironmentAccess> grants) {
    }

    /**
     * Onboard a person found in the Entra directory <em>as a normal user</em> (no admin /
     * env-admin rights) and immediately apply {@code grantReq} to them — all in one
     * transaction. Backs {@code POST /api/v1/access-grants} when the caller passes a
     * {@code directoryObjectId} instead of an existing user's email.
     *
     * @throws DirectoryLookupException 409 when {@code graph.directory.enabled} is off
     * @throws UserAlreadyExistsException when that person already has an {@code app_user}
     */
    @Transactional
    public OnboardResult onboardAndGrant(String directoryObjectId, AccessGrantRequestDTO grantReq, String actorUserId) {
        if (!graphDirectoryService.isEnabled()) {
            throw DirectoryLookupException.disabled();
        }

        OnboardUserDTO dto = new OnboardUserDTO();
        dto.setDirectoryObjectId(directoryObjectId);
        // admin / envAdmin left at their false defaults — onboard as a normal user.

        OnboardUserDTO.InitialGrant grant = new OnboardUserDTO.InitialGrant();
        grant.setEnvironmentId(grantReq.getEnvironmentId());
        grant.setAccessLevel(grantReq.getAccessLevel());
        grant.setScopeType(grantReq.getScopeType());
        grant.setGroupIds(grantReq.getGroupIds());
        grant.setDurationDays(grantReq.getDurationDays());
        grant.setNotes(grantReq.getNotes());
        dto.setInitialGrant(grant);

        return onboard(dto, actorUserId);
    }

    @Transactional
    public OnboardResult onboard(OnboardUserDTO dto, String actorUserId) {
        String directoryObjectId = trimToNull(dto.getDirectoryObjectId());

        String email;
        String displayName;
        String azureAdObjectId;

        if (directoryObjectId != null && graphDirectoryService.isEnabled()) {
            DirectoryUserDTO directoryUser = graphDirectoryService.fetchByObjectId(directoryObjectId);
            azureAdObjectId = directoryUser.directoryObjectId();
            email = trimToNull(directoryUser.email());
            if (email == null) {
                throw new ValidationException("Directory user " + directoryObjectId + " has no email or UPN");
            }
            displayName = firstNonBlank(directoryUser.displayName(), localPart(email));
        } else {
            if (directoryObjectId != null) {
                log.warn("directoryObjectId supplied but directory lookup is off — onboarding manually/unverified");
            }
            email = trimToNull(dto.getEmail());
            if (email == null) {
                throw new ValidationException("Email is required to onboard a user manually");
            }
            displayName = firstNonBlank(trimToNull(dto.getDisplayName()), localPart(email));
            azureAdObjectId = null;
        }

        Optional<User> existing = azureAdObjectId != null
                ? userRepository.findByAzureAdObjectId(azureAdObjectId)
                : Optional.empty();
        if (existing.isEmpty()) {
            existing = userRepository.findByEmail(email);
        }
        if (existing.isPresent()) {
            User u = existing.get();
            throw new UserAlreadyExistsException(u.getUserId(), u.isActive());
        }

        User user = userService.createOnboardedUser(email, displayName, azureAdObjectId,
                dto.isAdmin(), dto.isEnvAdmin(), actorUserId);

        List<EnvironmentAccess> grants = List.of();
        OnboardUserDTO.InitialGrant initialGrant = dto.getInitialGrant();
        if (initialGrant != null) {
            grants = environmentAccessService.grantScoped(actorUserId,
                    toGrantRequest(user.getEmail(), initialGrant));
        }
        return new OnboardResult(user, grants);
    }

    private AccessGrantRequestDTO toGrantRequest(String userEmail, OnboardUserDTO.InitialGrant g) {
        AccessGrantRequestDTO request = new AccessGrantRequestDTO();
        request.setUserEmail(userEmail);
        request.setEnvironmentId(g.getEnvironmentId());
        request.setAccessLevel(g.getAccessLevel());
        request.setScopeType(g.getScopeType());
        request.setGroupIds(g.getGroupIds());
        request.setDurationDays(g.getDurationDays());
        request.setNotes(g.getNotes());
        return request;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
