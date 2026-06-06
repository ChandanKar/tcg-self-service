package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.OperationEstimateDTO;
import com.tcgdigital.vmcontrol.dto.OperationExecutionDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.VmOperationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for VM operations (start/stop/restart).
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/operations")
@Tag(name = "VM Operations", description = "Operations for starting, stopping, and restarting VMs")
public class VmOperationsController {

    private final VmOperationsService operationsService;
    private final UserService userService;
    private final SecurityService securityService;

    public VmOperationsController(VmOperationsService operationsService, UserService userService, 
                                   SecurityService securityService) {
        this.operationsService = operationsService;
        this.userService = userService;
        this.securityService = securityService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Start a VM operation",
            description = "Initiates a start, stop, or restart operation on VMs in the environment. " +
                    "Requires USER level access on the environment. " +
                    "Operations respect VM group dependencies and execute in the correct order."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "202",
                    description = "Operation started successfully",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request or no VMs to operate on"),
            @ApiResponse(responseCode = "403", description = "Access denied - requires USER level access"),
            @ApiResponse(responseCode = "409", description = "Operation already in progress or lock conflict")
    })
    public ResponseEntity<OperationExecutionDTO> startOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody StartOperationDTO dto) {

        // Check USER level access for operations
        if (!securityService.hasEnvironmentAccessLevel(environmentId, AccessLevel.USER)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String effectiveUserId = userService.getCurrentUserId();

        OperationExecution execution = operationsService.startOperation(environmentId, effectiveUserId, dto);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(OperationExecutionDTO.fromEntity(execution));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List recent operations",
            description = "Retrieves the recent VM operations for an environment. Requires access to the environment."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of recent operations",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = OperationExecutionDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied")
    })
    public ResponseEntity<List<OperationExecutionDTO>> listOperations(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        // Check any level of access
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<OperationExecution> executions = operationsService.getRecentExecutions(environmentId);

        List<OperationExecutionDTO> dtos = executions.stream()
                .map(exec -> {
                    String displayName = resolveDisplayName(exec.getInitiatedByUserId());
                    return OperationExecutionDTO.fromEntity(exec, displayName);
                })
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{executionId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get operation status",
            description = "Retrieves the current status and details of a VM operation. Requires access to the environment."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Operation details",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Operation not found")
    })
    public ResponseEntity<OperationExecutionDTO> getOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Execution ID") @PathVariable String executionId) {

        // Check any level of access
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        OperationExecution execution = operationsService.getExecutionWithDetails(executionId);
        String displayName = resolveDisplayName(execution.getInitiatedByUserId());

        return ResponseEntity.ok(OperationExecutionDTO.fromEntityWithDetails(execution, displayName));
    }

    @GetMapping("/time-estimates")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get operation time estimates",
            description = "Returns average duration statistics (last 10 runs) for start/stop operations. " +
                    "Scope: environment (default), group (pass groupId), or VM (pass vmId). " +
                    "Requires access to the environment."
    )
    public ResponseEntity<OperationEstimateDTO> getEstimates(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Operation type: START or STOP") @RequestParam(defaultValue = "START") String operationType,
            @Parameter(description = "Optional: scope to a specific group") @RequestParam(required = false) String groupId,
            @Parameter(description = "Optional: scope to a specific VM")    @RequestParam(required = false) String vmId) {

        // Check any level of access
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        OperationEstimateDTO estimate = operationsService.getOperationEstimate(environmentId, operationType, groupId, vmId);
        return ResponseEntity.ok(estimate);
    }

    @PostMapping("/{executionId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Cancel an operation",
            description = "Cancels a pending or in-progress operation. Requires USER level access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Operation cancelled",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Operation cannot be cancelled"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Operation not found")
    })
    public ResponseEntity<OperationExecutionDTO> cancelOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Execution ID") @PathVariable String executionId) {

        // Check USER level access for operations
        if (!securityService.hasEnvironmentAccessLevel(environmentId, AccessLevel.USER)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String effectiveUserId = userService.getCurrentUserId();

        OperationExecution execution = operationsService.cancelExecution(executionId, effectiveUserId);

        return ResponseEntity.ok(OperationExecutionDTO.fromEntity(execution));
    }

    /**
     * Resolve user display name from user ID.
     * Returns the user ID if display name cannot be resolved.
     */
    private String resolveDisplayName(String userId) {
        try {
            User user = userService.getUserById(userId);
            return user.getDisplayName() != null ? user.getDisplayName() : userId;
        } catch (Exception e) {
            return userId;
        }
    }
}
