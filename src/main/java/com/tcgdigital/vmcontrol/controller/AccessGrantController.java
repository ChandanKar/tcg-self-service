package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AccessGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.EnvironmentAccessDTO;
import com.tcgdigital.vmcontrol.dto.UpdateAccessGrantDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserOnboardingService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Direct, scoped access grants — an admin / env-admin granting a user access to an environment
 * or to specific groups within it, with no request from the user. Group scope is gated by
 * {@code access.group-scope.enabled}.
 */
@RestController
@RequestMapping("/api/v1/access-grants")
@Tag(name = "Access Grants", description = "Admin-initiated environment / group access grants")
public class AccessGrantController {

    static final String SELF_CHANGE = "You cannot change your own access; ask another administrator";

    private final EnvironmentAccessService accessService;
    private final SecurityService securityService;
    private final UserService userService;
    private final UserOnboardingService userOnboardingService;
    private final VmGroupRepository vmGroupRepository;
    private final com.tcgdigital.vmcontrol.service.BulkAccessGrantService bulkGrants;

    public AccessGrantController(EnvironmentAccessService accessService,
                                SecurityService securityService,
                                UserService userService,
                                UserOnboardingService userOnboardingService,
                                VmGroupRepository vmGroupRepository,
                                com.tcgdigital.vmcontrol.service.BulkAccessGrantService bulkGrants) {
        this.accessService = accessService;
        this.securityService = securityService;
        this.userService = userService;
        this.userOnboardingService = userOnboardingService;
        this.vmGroupRepository = vmGroupRepository;
        this.bulkGrants = bulkGrants;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Grant access directly",
            description = "Grants access to an environment or to one or more groups within it, without a "
                    + "request. Identify the person by userEmail (an existing app user) or by "
                    + "directoryObjectId (an Entra directory user not yet in the app — onboarded as a "
                    + "normal user in the same transaction, ADMIN only). Requires manage-access rights on "
                    + "every targeted scope.")
    public ResponseEntity<List<EnvironmentAccessDTO>> grant(@Valid @RequestBody AccessGrantRequestDTO dto) {
        boolean hasEmail = dto.getUserEmail() != null && !dto.getUserEmail().isBlank();
        boolean hasDirectoryId = dto.getDirectoryObjectId() != null && !dto.getDirectoryObjectId().isBlank();
        if (hasEmail == hasDirectoryId) {
            throw new ValidationException("Provide exactly one of userEmail or directoryObjectId");
        }

        if (dto.getScopeType() == AccessScopeType.GROUP) {
            List<String> groupIds = dto.getGroupIds() == null ? List.of() : dto.getGroupIds();
            for (String groupId : groupIds) {
                if (!securityService.canManageGroupAccess(groupId)) {
                    throw new UnauthorizedException("You cannot manage access on group " + groupId);
                }
            }
        } else if (!securityService.canManageEnvironmentAccess(dto.getEnvironmentId())) {
            throw new UnauthorizedException("You cannot manage access on environment " + dto.getEnvironmentId());
        }

        if (hasEmail) {
            userService.getUserByEmail(dto.getUserEmail().trim())
                    .ifPresent(target -> securityService.assertNotSelf(target.getUserId(), SELF_CHANGE));
        }

        String actorUserId = userService.getCurrentUserId();
        List<EnvironmentAccess> grants;
        if (hasDirectoryId) {
            if (!securityService.isAdmin()) {
                throw new UnauthorizedException("Onboarding a user from the directory requires the ADMIN role");
            }
            grants = userOnboardingService
                    .onboardAndGrant(dto.getDirectoryObjectId().trim(), dto, actorUserId)
                    .grants();
        } else {
            grants = accessService.grantScoped(actorUserId, dto);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toDtos(grants));
    }

    /**
     * One paged list of grants across the environments the caller manages (U10-T01): status
     * ACTIVE (default), ENDING (within 7 days) or ENDED (in the last access.history-days), an
     * optional environment and a search. An environment outside the caller's scope is a 404.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "List grants with status filters and search")
    public org.springframework.data.domain.Page<EnvironmentAccessDTO> list(
            @RequestParam(required = false) String environmentId,
            @RequestParam(defaultValue = "ACTIVE") EnvironmentAccessService.GrantBucket status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<String> allowed = scope(environmentId);
        org.springframework.data.domain.Page<EnvironmentAccess> grants = accessService.searchGrants(allowed, environmentId,
                status, search, com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 100));
        List<EnvironmentAccessDTO> rows = toDtos(grants.getContent());
        return new org.springframework.data.domain.PageImpl<>(rows, grants.getPageable(), grants.getTotalElements());
    }

    /**
     * What granting one level to many people on many environments or groups would do (U10-T02):
     * one row per person and scope, New / Extends / Upgrades / No change with the reason. Nothing
     * is written. Every target must be one the caller manages (403 otherwise).
     */
    @PostMapping("/bulk/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Preview a bulk grant")
    public com.tcgdigital.vmcontrol.dto.BulkGrantPlanDTO bulkPreview(
            @Valid @RequestBody com.tcgdigital.vmcontrol.dto.BulkGrantRequestDTO dto) {
        return bulkGrants.preview(userService.getCurrentUserId(), dto);
    }

    /** Applies a bulk grant, all or nothing; returns what was done (same shape as the preview). */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Grant access to many people on many environments or groups")
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.BulkGrantPlanDTO> bulkGrant(
            @Valid @RequestBody com.tcgdigital.vmcontrol.dto.BulkGrantRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bulkGrants.apply(userService.getCurrentUserId(), dto));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Counts for the grants list header")
    public com.tcgdigital.vmcontrol.dto.AccessGrantSummaryDTO summary(@RequestParam(required = false) String environmentId) {
        return accessService.grantSummary(scope(environmentId), environmentId);
    }

    /** The environments the caller manages (null = all); 404 for one outside them. */
    private List<String> scope(String environmentId) {
        List<String> allowed = securityService.administeredEnvironmentIdsOrAll();
        if (environmentId != null && !environmentId.isBlank() && allowed != null && !allowed.contains(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.ResourceNotFoundException("Environment", environmentId);
        }
        return allowed;
    }

    @PatchMapping("/{accessId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change a grant's level or expiry")
    public ResponseEntity<EnvironmentAccessDTO> update(@PathVariable String accessId,
                                                       @Valid @RequestBody UpdateAccessGrantDTO dto) {
        EnvironmentAccess grant = accessService.getGrantById(accessId);
        assertCanManage(grant);
        // Extending, clearing the expiry of or raising one's own grant needs another administrator.
        securityService.assertNotSelf(grant.getUser().getUserId(), SELF_CHANGE);
        EnvironmentAccess updated = accessService.updateGrant(userService.getCurrentUserId(), accessId, dto);
        return ResponseEntity.ok(toDtos(List.of(updated)).get(0));
    }

    @DeleteMapping("/{accessId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revoke a grant")
    public ResponseEntity<Void> revoke(@PathVariable String accessId) {
        assertCanManage(accessService.getGrantById(accessId));
        accessService.revokeGrantById(userService.getCurrentUserId(), accessId);
        return ResponseEntity.noContent().build();
    }

    private void assertCanManage(EnvironmentAccess grant) {
        boolean ok = grant.getScopeType() == AccessScopeType.GROUP
                ? securityService.canManageGroupAccess(grant.getScopeId())
                : securityService.canManageEnvironmentAccess(grant.getEnvironment().getEnvironmentId());
        if (!ok) {
            throw new UnauthorizedException("You cannot manage this grant");
        }
    }

    /** Map to DTOs, resolving GROUP scope ids to group display names in one query. */
    private List<EnvironmentAccessDTO> toDtos(List<EnvironmentAccess> grants) {
        List<String> groupIds = grants.stream()
                .filter(g -> g.getScopeType() == AccessScopeType.GROUP)
                .map(EnvironmentAccess::getScopeId)
                .distinct()
                .toList();
        Map<String, String> groupNames = groupIds.isEmpty() ? Map.of()
                : vmGroupRepository.findAllById(groupIds).stream()
                        .collect(Collectors.toMap(VmGroup::getGroupId, VmGroup::getDisplayName));
        return grants.stream()
                .map(g -> EnvironmentAccessDTO.fromEntity(g,
                        g.getScopeType() == AccessScopeType.GROUP ? groupNames.get(g.getScopeId()) : null))
                .toList();
    }
}
