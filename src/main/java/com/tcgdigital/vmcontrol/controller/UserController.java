package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.UpdateUserRoleDTO;
import com.tcgdigital.vmcontrol.dto.UserDTO;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for User management operations.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Operations for managing users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List all users",
            description = "Retrieves a list of all users. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of users",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role")
    })
    public ResponseEntity<List<UserDTO>> listUsers(
            @Parameter(description = "Include inactive users")
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {

        List<User> users = includeInactive
                ? userService.getAllUsers()
                : userService.getAllActiveUsers();

        List<UserDTO> dtos = users.stream()
                .map(UserDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get current user",
            description = "Retrieves information about the currently authenticated user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved current user",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public ResponseEntity<UserDTO> getCurrentUser() {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(UserDTO.fromEntity(currentUser));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.getAttribute('oid')")
    @Operation(
            summary = "Get user by ID",
            description = "Retrieves detailed information about a specific user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved user",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserById(
            @Parameter(description = "User ID") @PathVariable String userId) {

        User user = userService.getUserById(userId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Search users",
            description = "Search users by email or display name"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved matching users",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserDTO.class))
                    )
            )
    })
    public ResponseEntity<List<UserDTO>> searchUsers(
            @Parameter(description = "Search term") @RequestParam String q) {

        List<User> users = userService.searchUsers(q);
        List<UserDTO> dtos = users.stream()
                .map(UserDTO::fromEntity)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PatchMapping("/{userId}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Toggle admin role",
            description = "Toggle admin role for a user. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Admin role toggled successfully",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Cannot remove the last admin"),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> toggleAdmin(
            @Parameter(description = "User ID") @PathVariable String userId) {

        String currentUserId = userService.getCurrentUserId();
        User user = userService.toggleAdmin(userId, currentUserId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @PatchMapping("/{userId}/env-admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Toggle environment admin role",
            description = "Toggle environment admin role for a user. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Environment admin role toggled successfully",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> toggleEnvAdmin(
            @Parameter(description = "User ID") @PathVariable String userId) {

        String currentUserId = userService.getCurrentUserId();
        User user = userService.toggleEnvAdmin(userId, currentUserId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @PatchMapping("/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Update user roles",
            description = "Update admin and/or env_admin roles for a user. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User roles updated successfully",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Cannot remove the last admin"),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> updateUserRoles(
            @Parameter(description = "User ID") @PathVariable String userId,
            @RequestBody UpdateUserRoleDTO dto) {

        String currentUserId = userService.getCurrentUserId();
        User user = userService.getUserById(userId);

        if (dto.getAdmin() != null) {
            user = userService.setAdmin(userId, dto.getAdmin(), currentUserId);
        }
        if (dto.getEnvAdmin() != null) {
            user = userService.setEnvAdmin(userId, dto.getEnvAdmin(), currentUserId);
        }

        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Deactivate user",
            description = "Deactivate a user account (soft delete). Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User deactivated successfully",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Cannot deactivate the last admin or yourself"),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> deactivateUser(
            @Parameter(description = "User ID") @PathVariable String userId) {

        String currentUserId = userService.getCurrentUserId();
        User user = userService.deactivateUser(userId, currentUserId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Reactivate user",
            description = "Reactivate a deactivated user account. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User reactivated successfully",
                    content = @Content(schema = @Schema(implementation = UserDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied - requires admin role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> reactivateUser(
            @Parameter(description = "User ID") @PathVariable String userId) {

        String currentUserId = userService.getCurrentUserId();
        User user = userService.reactivateUser(userId, currentUserId);
        return ResponseEntity.ok(UserDTO.fromEntity(user));
    }

    @GetMapping("/admins")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List admin users",
            description = "Retrieves a list of all admin users. Requires admin role."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of admin users",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserDTO.class))
                    )
            )
    })
    public ResponseEntity<List<UserDTO>> listAdminUsers() {
        List<User> admins = userService.getAdminUsers();
        List<UserDTO> dtos = admins.stream()
                .map(UserDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }
}

