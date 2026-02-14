package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.VmGroupDTO;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.service.VmGroupService;
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
 * REST controller for VmGroup management operations.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/groups")
@Tag(name = "VM Groups", description = "Operations for managing VM groups within environments")
public class VmGroupController {

    private final VmGroupService groupService;

    public VmGroupController(VmGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all groups in an environment",
            description = "Retrieves a list of all VM groups in the specified environment, ordered by sequence position"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of groups",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = VmGroupDTO.class))
                    )
            )
    })
    public ResponseEntity<List<VmGroupDTO>> listGroups(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<VmGroup> groups = groupService.getGroupsByEnvironmentId(environmentId);

        List<VmGroupDTO> dtos = groups.stream()
                .map(group -> VmGroupDTO.fromEntityWithCounts(
                        group,
                        groupService.getVmCount(group.getGroupId()),
                        groupService.getRunningVmCount(group.getGroupId())
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{groupId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get group details",
            description = "Retrieves detailed information about a specific VM group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved group",
                    content = @Content(schema = @Schema(implementation = VmGroupDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Group not found")
    })
    public ResponseEntity<VmGroupDTO> getGroup(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Group ID") @PathVariable String groupId) {

        VmGroup group = groupService.getGroupById(groupId);
        VmGroupDTO dto = VmGroupDTO.fromEntityWithCounts(
                group,
                groupService.getVmCount(groupId),
                groupService.getRunningVmCount(groupId)
        );

        return ResponseEntity.ok(dto);
    }

    @GetMapping("/start-order")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get groups in start order",
            description = "Retrieves groups sorted by dependency order for starting VMs"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved groups in start order",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = VmGroupDTO.class))
                    )
            )
    })
    public ResponseEntity<List<VmGroupDTO>> getGroupsInStartOrder(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        List<VmGroup> groups = groupService.getGroupsInStartOrder(environmentId);

        List<VmGroupDTO> dtos = groups.stream()
                .map(group -> VmGroupDTO.fromEntityWithCounts(
                        group,
                        groupService.getVmCount(group.getGroupId()),
                        groupService.getRunningVmCount(group.getGroupId())
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Create a new group",
            description = "Creates a new VM group within the specified environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Group created successfully",
                    content = @Content(schema = @Schema(implementation = VmGroupDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input or duplicate name/sequence")
    })
    public ResponseEntity<VmGroupDTO> createGroup(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody CreateVmGroupDTO dto) {

        VmGroup created = groupService.createGroup(environmentId, dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(VmGroupDTO.fromEntity(created));
    }

    @PutMapping("/{groupId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Update a group",
            description = "Updates an existing VM group's details"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Group updated successfully",
                    content = @Content(schema = @Schema(implementation = VmGroupDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Group not found")
    })
    public ResponseEntity<VmGroupDTO> updateGroup(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Group ID") @PathVariable String groupId,
            @Valid @RequestBody CreateVmGroupDTO dto) {

        VmGroup updated = groupService.updateGroup(groupId, dto);
        return ResponseEntity.ok(VmGroupDTO.fromEntity(updated));
    }

    @DeleteMapping("/{groupId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Delete a group",
            description = "Deletes a VM group (only if no VMs exist in it)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Group deleted successfully"),
            @ApiResponse(responseCode = "400", description = "Group has VMs and cannot be deleted"),
            @ApiResponse(responseCode = "404", description = "Group not found")
    })
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Group ID") @PathVariable String groupId) {

        groupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }
}

