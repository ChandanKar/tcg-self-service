package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.EksSyncService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentInsightsService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Environment management operations.
 */
@RestController
@RequestMapping("/api/v1/environments")
@Tag(name = "Environments", description = "Operations for managing environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final SecurityService securityService;
    private final EksSyncService eksSyncService;
    private final EnvironmentInsightsService environmentInsightsService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.EnvironmentCostService environmentCostService;
    private final EnvironmentAccessService environmentAccessService;

    public EnvironmentController(EnvironmentService environmentService,
                                 SecurityService securityService,
                                 EksSyncService eksSyncService,
                                 EnvironmentInsightsService environmentInsightsService,
                                 NotificationService notificationService,
                                 UserService userService,
                                 EnvironmentAccessService environmentAccessService,
                                 com.tcgdigital.vmcontrol.service.EnvironmentCostService environmentCostService) {
        this.environmentAccessService = environmentAccessService;
        this.environmentCostService = environmentCostService;
        this.environmentService = environmentService;
        this.securityService = securityService;
        this.eksSyncService = eksSyncService;
        this.environmentInsightsService = environmentInsightsService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments",
            description = "Retrieves environments the current user has access to. Admins see all environments."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of environments",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentDTO>> listEnvironments(
            @Parameter(description = "Include inactive environments (admin only)")
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {

        List<Environment> environments;

        // Admins and Env Admins see all environments
        if (securityService.isEnvAdmin()) {
            environments = includeInactive
                    ? environmentService.getAllEnvironments()
                    : environmentService.getAllActiveEnvironments();
        } else {
            // Regular users only see environments they have access to
            environments = environmentService.getEnvironmentsForCurrentUser();
        }

        List<EnvironmentDTO> dtos = toDtosWithBatchedCounts(environments);

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/page")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments, paginated",
            description = "Server-side-paginated, optionally name/description-filtered variant of the " +
                    "environments list, for the \"My Environments\" table. Admins see all environments; " +
                    "regular users see only environments they have access to."
    )
    public ResponseEntity<Page<EnvironmentDTO>> listEnvironmentsPaged(
            @Parameter(description = "Include inactive environments (admin only)")
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @Parameter(description = "Optional name/description search filter")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size,
                com.tcgdigital.vmcontrol.controller.support.Paging.DEFAULT_MAX_SIZE); // 400 on page < 0, size clamped
        Page<Environment> environments = securityService.isEnvAdmin()
                ? (includeInactive
                        ? environmentService.getAllEnvironments(search, pageable)
                        : environmentService.getAllActiveEnvironments(search, pageable))
                : environmentService.getEnvironmentsForCurrentUser(search, pageable);

        List<String> environmentIds = environments.getContent().stream()
                .map(Environment::getEnvironmentId)
                .toList();
        Map<String, EnvironmentService.EnvironmentCounts> counts = environmentService.getBatchCounts(environmentIds);
        Map<String, EnvironmentService.LockSummary> locks = environmentService.getLockSummaries(environmentIds);

        Page<EnvironmentDTO> dtos = environments.map(env -> toDtoWithCounts(env, counts, locks));

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments available for access request",
            description = "Retrieves active environments the current user does NOT have access to. Used for Request Access feature."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of available environments",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentDTO>> listAvailableEnvironments() {
        List<Environment> environments = environmentService.getEnvironmentsWithoutAccessForCurrentUser();

        List<EnvironmentDTO> dtos = toDtosWithBatchedCounts(environments);

        return ResponseEntity.ok(dtos);
    }

    /**
     * Attaches group/VM/running/region counts to a batch of environments with a small, fixed
     * number of queries total, regardless of list size — avoids issuing them per environment
     * (see EnvironmentService.getBatchCounts). Shared by every environment-listing endpoint
     * (list, paged, available) so they can't drift out of sync with each other.
     */
    private List<EnvironmentDTO> toDtosWithBatchedCounts(List<Environment> environments) {
        List<String> environmentIds = environments.stream().map(Environment::getEnvironmentId).toList();
        var counts = environmentService.getBatchCounts(environmentIds);
        var locks = environmentService.getLockSummaries(environmentIds);

        return environments.stream()
                .map(env -> toDtoWithCounts(env, counts, locks))
                .toList();
    }

    private EnvironmentDTO toDtoWithCounts(Environment env, Map<String, EnvironmentService.EnvironmentCounts> counts,
                                           Map<String, EnvironmentService.LockSummary> locks) {
        var c = counts.get(env.getEnvironmentId());
        EnvironmentDTO dto = EnvironmentDTO.fromEntityWithCounts(env, c.groupCount(), c.vmCount(), c.runningVmCount(), c.regions());
        EnvironmentService.LockSummary lock = locks.get(env.getEnvironmentId());
        if (lock != null) {
            dto.setLocked(true);
            dto.setLockedByUserId(lock.lockedByUserId());
            dto.setLockedByDisplayName(lock.lockedByDisplayName());
            dto.setLockedAt(lock.lockedAt());
        }
        return dto;
    }

    @GetMapping("/{environmentId}/cost")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Environment cost for its users",
            description = "Month to date, trend, run-rate forecast, top VMs, savings and the next scheduled stop/start. "
                    + "Anyone who can view the environment; a group-only grant sees its groups only (scope GROUPS)."
    )
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.EnvironmentCostDTO> getEnvironmentCost(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanView(environmentId); // 404 without access: no cost data leaks
        return ResponseEntity.ok(environmentCostService.getCost(environmentId));
    }

    @GetMapping("/{environmentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get environment details",
            description = "Retrieves detailed information about a specific environment. User must have access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved environment",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> getEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        // Check access
        if (!securityService.canViewEnvironment(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Environment environment = environmentService.getEnvironmentById(environmentId);
        EnvironmentDTO dto = toDtosWithBatchedCounts(List.of(environment)).get(0);

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{environmentId}/insights")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get environment insights",
            description = "Returns aggregate inventory, utilization, storage, idle, and recommendation data for an environment."
    )
    public ResponseEntity<EnvironmentInsightsDTO> getEnvironmentInsights(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        if (!securityService.canViewEnvironment(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Restrict to the caller's visible groups — a no-op for anyone with environment-wide
        // access (getVisibleGroupIds returns every group for them).
        return ResponseEntity.ok(environmentInsightsService.getInsights(
                environmentId, securityService.getVisibleGroupIds(environmentId)));
    }

    @GetMapping("/discover/eks")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Discover unregistered EKS clusters",
            description = "Returns EKS clusters that exist in AWS but are not yet registered as environments, with their region"
    )
    public ResponseEntity<List<EksClusterInfoDTO>> discoverEksClusters() {
        return ResponseEntity.ok(eksSyncService.getUnregisteredEksClusters());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Create a new environment",
            description = "Creates a new environment for organizing VMs"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Environment created successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input or duplicate name")
    })
    public ResponseEntity<EnvironmentDTO> createEnvironment(
            @Valid @RequestBody CreateEnvironmentDTO dto) {

        Environment created = environmentService.createEnvironment(dto);
        grantCreatorAdmin(created);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnvironmentDTO.fromEntity(created));
    }

    /**
     * A non-global admin who creates an environment gets an ENVIRONMENT ADMIN grant on it, so they
     * keep administering it when security.env-admin.scope=assigned (a new environment has no grants).
     */
    private void grantCreatorAdmin(Environment created) {
        User creator = userService.getCurrentUser();
        if (creator == null || creator.isAdmin() || creator.getEmail() == null) {
            return;
        }
        AccessGrantRequestDTO grant = new AccessGrantRequestDTO();
        grant.setUserEmail(creator.getEmail());
        grant.setEnvironmentId(created.getEnvironmentId());
        grant.setAccessLevel(AccessLevel.ADMIN);
        grant.setScopeType(AccessScopeType.ENVIRONMENT);
        grant.setNotes("Created the environment");
        environmentAccessService.grantScoped(creator.getUserId(), grant);
    }

    @PutMapping("/{environmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Update an environment",
            description = "Updates an existing environment's details"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Environment updated successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> updateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody UpdateEnvironmentDTO dto) {
        securityService.assertCanAdminister(environmentId);

        Environment updated = environmentService.updateEnvironment(environmentId, dto);
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(updated));
    }

    @DeleteMapping("/{environmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Deactivate an environment",
            description = "Soft deletes an environment by marking it as inactive; its active lock is released. "
                    + "Refused while an operation is running. It can be reactivated later."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Environment deactivated successfully"),
            @ApiResponse(responseCode = "400", description = "An operation is running in the environment"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Void> deactivateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);

        environmentService.deactivateEnvironment(environmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{environmentId}/reactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Reactivate an environment",
            description = "Reverses a soft delete, making a deactivated environment active again"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Environment reactivated successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> reactivateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);

        Environment reactivated = environmentService.reactivateEnvironment(environmentId);
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(reactivated));
    }

    @PostMapping("/{environmentId}/notify-stop")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Notify environment members that it is being stopped",
            description = "Emails (and bell-notifies) everyone with active access to this environment. "
                    + "An explicit admin action, independent of notification.email.* flags — the click is the opt-in."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notified — body reports how many people were emailed"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Map<String, Object>> notifyStop(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @RequestBody(required = false) Map<String, String> body) {
        securityService.assertCanAdminister(environmentId);

        Environment environment = environmentService.getEnvironmentById(environmentId);
        String reason = body != null ? body.get("reason") : null;
        String actorUserId = userService.getCurrentUserId();
        int emailed = notificationService.notifyStopEnvironment(
                environmentId, environment.getDisplayName(), actorUserId, reason);
        return ResponseEntity.ok(Map.of("emailedCount", emailed));
    }
}
