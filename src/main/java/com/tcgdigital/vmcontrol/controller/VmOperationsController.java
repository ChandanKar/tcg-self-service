package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.OperationExecutionDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.OperationExecution;
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

    private static final String DEFAULT_USER_ID = "dev-user-001";

    private final VmOperationsService operationsService;

    public VmOperationsController(VmOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Start a VM operation",
            description = "Initiates a start, stop, or restart operation on VMs in the environment. " +
                    "Operations respect VM group dependencies and execute in the correct order."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "202",
                    description = "Operation started successfully",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request or no VMs to operate on"),
            @ApiResponse(responseCode = "409", description = "Operation already in progress or lock conflict")
    })
    public ResponseEntity<OperationExecutionDTO> startOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody StartOperationDTO dto,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;

        OperationExecution execution = operationsService.startOperation(environmentId, effectiveUserId, dto);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(OperationExecutionDTO.fromEntity(execution));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List recent operations",
            description = "Retrieves the recent VM operations for an environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of recent operations",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = OperationExecutionDTO.class))
                    )
            )
    })
    public ResponseEntity<List<OperationExecutionDTO>> listOperations(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<OperationExecution> executions = operationsService.getRecentExecutions(environmentId);

        List<OperationExecutionDTO> dtos = executions.stream()
                .map(OperationExecutionDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{executionId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get operation status",
            description = "Retrieves the current status and details of a VM operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Operation details",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Operation not found")
    })
    public ResponseEntity<OperationExecutionDTO> getOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Execution ID") @PathVariable String executionId) {

        OperationExecution execution = operationsService.getExecutionWithDetails(executionId);

        return ResponseEntity.ok(OperationExecutionDTO.fromEntityWithDetails(execution));
    }

    @PostMapping("/{executionId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Cancel an operation",
            description = "Cancels a pending or in-progress operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Operation cancelled",
                    content = @Content(schema = @Schema(implementation = OperationExecutionDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Operation cannot be cancelled"),
            @ApiResponse(responseCode = "404", description = "Operation not found")
    })
    public ResponseEntity<OperationExecutionDTO> cancelOperation(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Execution ID") @PathVariable String executionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;

        OperationExecution execution = operationsService.cancelExecution(executionId, effectiveUserId);

        return ResponseEntity.ok(OperationExecutionDTO.fromEntity(execution));
    }
}

