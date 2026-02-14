package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Environment Access management operations.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Environment Access", description = "Operations for managing environment access and access requests")
public class EnvironmentAccessController {

    private final EnvironmentAccessService accessService;
    private final UserService userService;

    public EnvironmentAccessController(EnvironmentAccessService accessService, UserService userService) {
        this.accessService = accessService;
        this.userService = userService;
    }

    // ============= Access Grant Endpoints =============

    @GetMapping("/environments/{environmentId}/access")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "List users with access to environment",
            description = "Retrieves all users with active access to the specified environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved access list",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentAccessDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<List<EnvironmentAccessDTO>> listEnvironmentAccess(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<EnvironmentAccess> accessList = accessService.getAccessForEnvironment(environmentId);
        List<EnvironmentAccessDTO> dtos = accessList.stream()
                .map(EnvironmentAccessDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/environments/{environmentId}/access")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Grant access to environment",
            description = "Directly grants access to a user (admin operation, bypasses request workflow)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Access granted successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentAccessDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment or user not found")
    })
    public ResponseEntity<EnvironmentAccessDTO> grantAccess(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody GrantAccessDTO dto) {

        String grantedByUserId = userService.getCurrentUserId();
        EnvironmentAccess access = accessService.grantAccess(environmentId, grantedByUserId, dto);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnvironmentAccessDTO.fromEntity(access));
    }

    @DeleteMapping("/environments/{environmentId}/access/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Revoke access from environment",
            description = "Revokes a user's access to an environment"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Access revoked successfully"),
            @ApiResponse(responseCode = "400", description = "User does not have access"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Void> revokeAccess(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "User ID") @PathVariable String userId) {

        String revokedByUserId = userService.getCurrentUserId();
        accessService.revokeAccess(environmentId, userId, revokedByUserId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me/access")
    @Operation(
            summary = "Get my environment access",
            description = "Retrieves all environments the current user has access to"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved access list",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentAccessDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentAccessDTO>> getMyAccess() {
        String currentUserId = userService.getCurrentUserId();
        List<EnvironmentAccess> accessList = accessService.getAccessForUser(currentUserId);
        List<EnvironmentAccessDTO> dtos = accessList.stream()
                .map(EnvironmentAccessDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    // ============= Access Request Endpoints =============

    @PostMapping("/environments/{environmentId}/access-requests")
    @Operation(
            summary = "Request access to environment",
            description = "Submit an access request for the specified environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Access request submitted successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input or already have access/pending request"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentAccessRequestDTO> createAccessRequest(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody CreateAccessRequestDTO dto) {

        String requesterId = userService.getCurrentUserId();
        EnvironmentAccessRequest request = accessService.createAccessRequest(environmentId, requesterId, dto);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnvironmentAccessRequestDTO.fromEntity(request));
    }

    @GetMapping("/access-requests/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "List pending access requests",
            description = "Retrieves all pending access requests for review"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved pending requests",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied")
    })
    public ResponseEntity<List<EnvironmentAccessRequestDTO>> listPendingRequests() {
        List<EnvironmentAccessRequest> requests = accessService.getPendingRequests();
        List<EnvironmentAccessRequestDTO> dtos = requests.stream()
                .map(EnvironmentAccessRequestDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/access-requests/my")
    @Operation(
            summary = "Get my access requests",
            description = "Retrieves all access requests submitted by the current user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved requests",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentAccessRequestDTO>> getMyRequests() {
        String currentUserId = userService.getCurrentUserId();
        List<EnvironmentAccessRequest> requests = accessService.getRequestsByUser(currentUserId);
        List<EnvironmentAccessRequestDTO> dtos = requests.stream()
                .map(EnvironmentAccessRequestDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/access-requests/{requestId}")
    @Operation(
            summary = "Get access request details",
            description = "Retrieves details of a specific access request"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved request",
                    content = @Content(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    public ResponseEntity<EnvironmentAccessRequestDTO> getAccessRequest(
            @Parameter(description = "Request ID") @PathVariable String requestId) {

        EnvironmentAccessRequest request = accessService.getAccessRequest(requestId);
        return ResponseEntity.ok(EnvironmentAccessRequestDTO.fromEntity(request));
    }

    @PostMapping("/access-requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Approve access request",
            description = "Approves a pending access request and grants access to the user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Request approved successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentAccessDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Request is not pending"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    public ResponseEntity<EnvironmentAccessDTO> approveRequest(
            @Parameter(description = "Request ID") @PathVariable String requestId,
            @RequestBody(required = false) ReviewAccessRequestDTO dto) {

        String reviewerUserId = userService.getCurrentUserId();
        String notes = dto != null ? dto.getNotes() : null;
        EnvironmentAccess access = accessService.approveRequest(requestId, reviewerUserId, notes);

        return ResponseEntity.ok(EnvironmentAccessDTO.fromEntity(access));
    }

    @PostMapping("/access-requests/{requestId}/deny")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Deny access request",
            description = "Denies a pending access request"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Request denied successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Request is not pending"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    public ResponseEntity<EnvironmentAccessRequestDTO> denyRequest(
            @Parameter(description = "Request ID") @PathVariable String requestId,
            @RequestBody(required = false) ReviewAccessRequestDTO dto) {

        String reviewerUserId = userService.getCurrentUserId();
        String reason = dto != null ? dto.getNotes() : null;
        EnvironmentAccessRequest request = accessService.denyRequest(requestId, reviewerUserId, reason);

        return ResponseEntity.ok(EnvironmentAccessRequestDTO.fromEntity(request));
    }

    @DeleteMapping("/access-requests/{requestId}")
    @Operation(
            summary = "Cancel access request",
            description = "Cancels a pending access request (can only cancel own requests)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Request cancelled successfully"),
            @ApiResponse(responseCode = "400", description = "Request is not pending or not owned by user"),
            @ApiResponse(responseCode = "404", description = "Request not found")
    })
    public ResponseEntity<Void> cancelRequest(
            @Parameter(description = "Request ID") @PathVariable String requestId) {

        String currentUserId = userService.getCurrentUserId();
        accessService.cancelRequest(requestId, currentUserId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/environments/{environmentId}/access-requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "List access requests for environment",
            description = "Retrieves all access requests for a specific environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved requests",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentAccessRequestDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<List<EnvironmentAccessRequestDTO>> listEnvironmentAccessRequests(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<EnvironmentAccessRequest> requests = accessService.getRequestsForEnvironment(environmentId);
        List<EnvironmentAccessRequestDTO> dtos = requests.stream()
                .map(EnvironmentAccessRequestDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }
}

