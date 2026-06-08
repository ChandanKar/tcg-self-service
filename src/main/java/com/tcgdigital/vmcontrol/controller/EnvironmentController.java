package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.EksSyncService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    public EnvironmentController(EnvironmentService environmentService,
                                 SecurityService securityService,
                                 EksSyncService eksSyncService) {
        this.environmentService = environmentService;
        this.securityService = securityService;
        this.eksSyncService = eksSyncService;
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

        List<EnvironmentDTO> dtos = environments.stream()
                .map(env -> EnvironmentDTO.fromEntityWithCounts(
                        env,
                        environmentService.getGroupCount(env.getEnvironmentId()),
                        environmentService.getVmCount(env.getEnvironmentId())
                ))
                .toList();

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

        List<EnvironmentDTO> dtos = environments.stream()
                .map(env -> EnvironmentDTO.fromEntityWithCounts(
                        env,
                        environmentService.getGroupCount(env.getEnvironmentId()),
                        environmentService.getVmCount(env.getEnvironmentId())
                ))
                .toList();

        return ResponseEntity.ok(dtos);
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
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Environment environment = environmentService.getEnvironmentById(environmentId);
        EnvironmentDTO dto = EnvironmentDTO.fromEntityWithCounts(
                environment,
                environmentService.getGroupCount(environmentId),
                environmentService.getVmCount(environmentId)
        );

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/discover/eks")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Discover unregistered EKS clusters",
            description = "Returns EKS cluster names that exist in AWS but are not yet registered as environments"
    )
    public ResponseEntity<List<String>> discoverEksClusters() {
        return ResponseEntity.ok(eksSyncService.getUnregisteredEksClusters(defaultRegion));
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
}
