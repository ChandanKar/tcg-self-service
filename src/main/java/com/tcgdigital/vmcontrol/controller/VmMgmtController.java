package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.dto.VmGroupDTO;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.VmGroupService;
import com.tcgdigital.vmcontrol.service.VmService;
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

import java.util.ArrayList;
import java.util.List;

/**
 * REST controller for VM management operations.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/vms")
@Tag(name = "Virtual Machines", description = "Operations for managing VMs within environments")
public class VmMgmtController {

    private final VmService vmService;
    private final VmGroupService groupService;
    private final SecurityService securityService;
    private final UserService userService;

    public VmMgmtController(VmService vmService, VmGroupService groupService,
                             SecurityService securityService, UserService userService) {
        this.vmService = vmService;
        this.groupService = groupService;
        this.securityService = securityService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all VMs in an environment",
            description = "Retrieves a list of all VMs in the specified environment, grouped by their VM groups. " +
                    "Requires access to the environment."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of VMs grouped by groups",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = VmGroupWithVmsDTO.class))
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Access denied")
    })
    public ResponseEntity<List<VmGroupWithVmsDTO>> listVms(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        // Check access
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<VmGroup> groups = groupService.getGroupsByEnvironmentId(environmentId);
        List<VmGroupWithVmsDTO> result = new ArrayList<>();

        for (VmGroup group : groups) {
            List<Vm> vms = vmService.getVmsByGroupId(group.getGroupId());
            VmGroupWithVmsDTO dto = new VmGroupWithVmsDTO();
            dto.setGroup(VmGroupDTO.fromEntityWithCounts(
                    group,
                    vms.size(),
                    (int) vms.stream().filter(vm -> vm.getStatus() == com.tcgdigital.vmcontrol.model.VmStatus.RUNNING).count()
            ));
            dto.setVms(vms.stream().map(VmDTO::fromEntity).toList());
            result.add(dto);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{vmId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get VM details",
            description = "Retrieves detailed information about a specific VM. Requires access to the environment."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved VM",
                    content = @Content(schema = @Schema(implementation = VmDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<VmDTO> getVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {

        // Check access
        if (!securityService.hasEnvironmentAccess(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Vm vm = vmService.getVmById(vmId);
        return ResponseEntity.ok(VmDTO.fromEntity(vm));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Register a new VM",
            description = "Registers a new VM in the specified group within the environment"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "VM registered successfully",
                    content = @Content(schema = @Schema(implementation = VmDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input or duplicate VM")
    })
    public ResponseEntity<VmDTO> registerVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody RegisterVmDTO dto) {

        Vm created = vmService.registerVm(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(VmDTO.fromEntity(created));
    }

    @PutMapping("/{vmId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Update a VM",
            description = "Updates an existing VM's details"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "VM updated successfully",
                    content = @Content(schema = @Schema(implementation = VmDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<VmDTO> updateVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId,
            @Valid @RequestBody RegisterVmDTO dto) {

        Vm updated = vmService.updateVm(vmId, dto);
        return ResponseEntity.ok(VmDTO.fromEntity(updated));
    }

    @DeleteMapping("/{vmId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Delete a VM",
            description = "Unregisters a VM from the platform (does not affect the actual cloud VM)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "VM deleted successfully"),
            @ApiResponse(responseCode = "400", description = "VM has dependents and cannot be deleted"),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<Void> deleteVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {

        vmService.deleteVm(vmId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{vmId}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Acknowledge an auto-discovered VM",
            description = "Clears the discovery_pending flag after admin has reviewed and placed the VM in the correct group."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "VM acknowledged successfully"),
            @ApiResponse(responseCode = "400", description = "VM is not pending review"),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<VmDTO> acknowledgeVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {

        String userId = userService.getCurrentUserId();
        Vm vm = vmService.acknowledgeVm(vmId, userId);
        return ResponseEntity.ok(VmDTO.fromEntity(vm));
    }

    /**
     * DTO for VM list grouped by groups.
     */
    public static class VmGroupWithVmsDTO {
        private VmGroupDTO group;
        private List<VmDTO> vms;

        public VmGroupDTO getGroup() {
            return group;
        }

        public void setGroup(VmGroupDTO group) {
            this.group = group;
        }

        public List<VmDTO> getVms() {
            return vms;
        }

        public void setVms(List<VmDTO> vms) {
            this.vms = vms;
        }
    }
}

