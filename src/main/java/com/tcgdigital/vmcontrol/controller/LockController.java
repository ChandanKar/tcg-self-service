package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.LockHistory;
import com.tcgdigital.vmcontrol.service.LockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for environment lock management.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/lock")
@Tag(name = "Environment Locks", description = "Operations for managing environment locks")
public class LockController {

    private static final String DEFAULT_USER_ID = "dev-user-001"; // Mock user for development

    private final LockService lockService;

    public LockController(LockService lockService) {
        this.lockService = lockService;
    }

    @GetMapping
    @Operation(
            summary = "Get current lock status",
            description = "Retrieves the current lock status for an environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lock status retrieved",
                    content = @Content(schema = @Schema(implementation = LockStatusDTO.class))
            )
    })
    public ResponseEntity<LockStatusDTO> getLockStatus(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        Optional<EnvironmentLock> lock = lockService.getCurrentLock(environmentId);

        if (lock.isPresent()) {
            return ResponseEntity.ok(LockStatusDTO.fromEntity(lock.get()));
        } else {
            return ResponseEntity.ok(LockStatusDTO.noLock());
        }
    }

    @PostMapping("/acquire")
    @Operation(
            summary = "Acquire lock on environment",
            description = "Acquires an exclusive lock on the environment. Only one user can hold the lock at a time."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lock acquired successfully",
                    content = @Content(schema = @Schema(implementation = LockStatusDTO.class))
            ),
            @ApiResponse(responseCode = "409", description = "Lock already held by another user")
    })
    public ResponseEntity<LockStatusDTO> acquireLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody(required = false) AcquireLockDTO dto,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;
        String reason = dto != null ? dto.getReason() : null;
        Integer duration = dto != null ? dto.getExpectedDurationMinutes() : null;

        EnvironmentLock lock = lockService.acquireLock(environmentId, effectiveUserId, reason, duration);
        return ResponseEntity.ok(LockStatusDTO.fromEntity(lock));
    }

    @PostMapping("/release")
    @Operation(
            summary = "Release lock on environment",
            description = "Releases the lock held by the current user"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lock released successfully"),
            @ApiResponse(responseCode = "400", description = "No active lock exists"),
            @ApiResponse(responseCode = "403", description = "User does not hold the lock")
    })
    public ResponseEntity<Void> releaseLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;

        lockService.releaseLock(environmentId, effectiveUserId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/break")
    @Operation(
            summary = "Break lock (admin operation)",
            description = "Forcibly breaks the lock held by another user. Requires admin privileges."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lock broken successfully"),
            @ApiResponse(responseCode = "400", description = "No active lock to break")
    })
    public ResponseEntity<Void> breakLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody BreakLockDTO dto,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;

        lockService.breakLock(environmentId, effectiveUserId, dto.getReason());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/history")
    @Operation(
            summary = "Get lock history",
            description = "Retrieves recent lock history for the environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lock history retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = LockHistoryDTO.class))
                    )
            )
    })
    public ResponseEntity<List<LockHistoryDTO>> getLockHistory(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<LockHistory> history = lockService.getLockHistory(environmentId);
        List<LockHistoryDTO> dtos = history.stream()
                .map(LockHistoryDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }
}

