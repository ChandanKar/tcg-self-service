package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.EksSyncService;
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

    public EnvironmentController(EnvironmentService environmentService,
                                 SecurityService securityService,
                                 EksSyncService eksSyncService,
                                 EnvironmentInsightsService environmentInsightsService,
                                 NotificationService notificationService,
                                 UserService userService) {
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

        Pageable pageable = PageRequest.of(page, size);
        Page<Environment> environments = securityService.isEnvAdmin()
                ? (includeInactive
                        ? environmentService.getAllEnvironments(search, pageable)
                        : environmentService.getAllActiveEnvironments(search, pageable))
                : environmentService.getEnvironmentsForCurrentUser(search, pageable);

        List<String> environmentIds = environments.getContent().stream()
                .map(Environment::getEnvironmentId)
                .toList();
        Map<String, EnvironmentService.EnvironmentCounts> counts = environmentService.getBatchCounts(environmentIds);

        Page<EnvironmentDTO> dtos = environments.map(env -> toDtoWithCounts(env, counts));

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

        return environments.stream()
                .map(env -> toDtoWithCounts(env, counts))
                .toList();
    }

    private EnvironmentDTO toDtoWithCounts(Environment env, Map<String, EnvironmentService.EnvironmentCounts> counts) {
        var c = counts.get(env.getEnvironmentId());
        return EnvironmentDTO.fromEntityWithCounts(env, c.groupCount(), c.vmCount(), c.runningVmCount(), c.regions());
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnvironmentDTO.fromEntity(created));
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

        Environment updated = environmentService.updateEnvironment(environmentId, dto);
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(updated));
    }

    @DeleteMapping("/{environmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Deactivate an environment",
            description = "Soft deletes an environment by marking it as inactive"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Environment deactivated successfully"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Void> deactivateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

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

        Environment environment = environmentService.getEnvironmentById(environmentId);
        String reason = body != null ? body.get("reason") : null;
        String actorUserId = userService.getCurrentUserId();
        int emailed = notificationService.notifyStopEnvironment(
                environmentId, environment.getDisplayName(), actorUserId, reason);
        return ResponseEntity.ok(Map.of("emailedCount", emailed));
    }
}
