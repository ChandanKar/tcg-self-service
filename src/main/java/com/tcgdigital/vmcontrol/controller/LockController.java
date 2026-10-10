package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.LockHistory;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.LockService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for environment lock management.
 * Reading a lock needs view rights on the environment, acquiring needs operate rights, breaking
 * needs an admin role plus administer rights; release stays limited to the holder (LockService).
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/lock")
@Tag(name = "Environment Locks", description = "Operations for managing environment locks")
public class LockController {

    private final LockService lockService;
    private final UserService userService;
    private final SecurityService securityService;
    private final boolean lockExpiryEnabled;

    public LockController(LockService lockService, UserService userService, SecurityService securityService,
                          @org.springframework.beans.factory.annotation.Value("${locks.expiry.enabled:false}") boolean lockExpiryEnabled) {
        this.lockService = lockService;
        this.userService = userService;
        this.securityService = securityService;
        this.lockExpiryEnabled = lockExpiryEnabled;
    }

    /**
     * Fill what the current user may do with this lock (E07-T05). Break follows POST /break: an
     * ADMIN or ENV_ADMIN role plus administer rights, on someone else's lock.
     */
    private LockStatusDTO withViewerFlags(LockStatusDTO dto, String environmentId) {
        String me = userService.getCurrentUserId();
        boolean mine = dto.isLocked() && me != null && me.equals(dto.getLockedByUserId());
        dto.setCanAcquire(!dto.isLocked() && securityService.canOperateInEnvironment(environmentId));
        dto.setCanRelease(mine);
        dto.setCanExtend(mine && dto.getExpiresAt() != null);
        dto.setCanBreak(dto.isLocked() && !mine && securityService.isEnvAdmin()
                && securityService.canAdministerEnvironment(environmentId));
        dto.setLockExpiryEnabled(lockExpiryEnabled);
        return dto;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
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

        securityService.assertCanView(environmentId);
        Optional<EnvironmentLock> lock = lockService.getCurrentLock(environmentId);

        if (lock.isPresent()) {
            String displayName = resolveDisplayName(lock.get().getLockedByUserId());
            return ResponseEntity.ok(withViewerFlags(LockStatusDTO.fromEntity(lock.get(), displayName), environmentId));
        } else {
            return ResponseEntity.ok(withViewerFlags(LockStatusDTO.noLock(), environmentId));
        }
    }

    @PostMapping("/acquire")
    @PreAuthorize("isAuthenticated()")
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
            @ApiResponse(responseCode = "400", description = "Environment is inactive"),
            @ApiResponse(responseCode = "403", description = "No operate rights on this environment"),
            @ApiResponse(responseCode = "409", description = "Lock already held by another user")
    })
    public ResponseEntity<LockStatusDTO> acquireLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody(required = false) AcquireLockDTO dto) {

        securityService.assertCanOperate(environmentId);
        String effectiveUserId = userService.getCurrentUserId();
        String reason = dto != null ? dto.getReason() : null;
        Integer duration = dto != null ? dto.getExpectedDurationMinutes() : null;

        EnvironmentLock lock = lockService.acquireLock(environmentId, effectiveUserId, reason, duration);
        String displayName = resolveDisplayName(lock.getLockedByUserId());
        return ResponseEntity.ok(withViewerFlags(LockStatusDTO.fromEntity(lock, displayName), environmentId));
    }

    @PostMapping("/release")
    @PreAuthorize("isAuthenticated()")
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
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        String effectiveUserId = userService.getCurrentUserId();

        lockService.releaseLock(environmentId, effectiveUserId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/extend")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Extend my lock",
            description = "Extends the current user's lock by 15-480 minutes, from its expiry (or from now if that has "
                    + "passed). Only the holder may extend; the total may not exceed locks.max-duration-minutes."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lock extended"),
            @ApiResponse(responseCode = "400", description = "Invalid minutes, no expiry, or over the maximum duration"),
            @ApiResponse(responseCode = "403", description = "User does not hold the lock")
    })
    public ResponseEntity<LockStatusDTO> extendLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @jakarta.validation.Valid @RequestBody com.tcgdigital.vmcontrol.dto.ExtendLockDTO dto) {
        String effectiveUserId = userService.getCurrentUserId();
        EnvironmentLock lock = lockService.extend(environmentId, effectiveUserId, dto.getMinutes());
        String displayName = resolveDisplayName(lock.getLockedByUserId());
        return ResponseEntity.ok(withViewerFlags(LockStatusDTO.fromEntity(lock, displayName), environmentId));
    }

    @PostMapping("/break")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Break lock (admin operation)",
            description = "Forcibly breaks the lock held by another user. Requires admin privileges."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lock broken successfully"),
            @ApiResponse(responseCode = "400", description = "No active lock to break"),
            @ApiResponse(responseCode = "403", description = "Does not administer this environment")
    })
    public ResponseEntity<Void> breakLock(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody BreakLockDTO dto) {

        securityService.assertCanAdminister(environmentId);
        String effectiveUserId = userService.getCurrentUserId();

        lockService.breakLock(environmentId, effectiveUserId, dto.getReason());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/history")
    @PreAuthorize("isAuthenticated()")
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

        securityService.assertCanView(environmentId);
        List<LockHistory> history = lockService.getLockHistory(environmentId);
        List<LockHistoryDTO> dtos = history.stream()
                .map(h -> {
                    String displayName = resolveDisplayName(h.getPerformedByUserId());
                    return LockHistoryDTO.fromEntity(h, displayName);
                })
                .toList();

        return ResponseEntity.ok(dtos);
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
            // User might have been deleted or not found
            return userId;
        }
    }
}
