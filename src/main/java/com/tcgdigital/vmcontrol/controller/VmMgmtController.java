package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.VmInventoryDTO;
import com.tcgdigital.vmcontrol.dto.VmMetricsDTO;
import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.dto.VmGroupDTO;
import com.tcgdigital.vmcontrol.dto.VmUtilizationSummaryDTO;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.VmGroupService;
import com.tcgdigital.vmcontrol.service.VmInventoryService;
import com.tcgdigital.vmcontrol.service.VmMetricsService;
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
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for VM management operations.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/vms")
@Tag(name = "Virtual Machines", description = "Operations for managing VMs within environments")
public class VmMgmtController {

    private static final int DEFAULT_VM_PAGE_SIZE = 25;

    private final VmService vmService;
    private final VmGroupService groupService;
    private final SecurityService securityService;
    private final UserService userService;
    private final VmInventoryService inventoryService;
    private final VmMetricsService metricsService;

    public VmMgmtController(VmService vmService, VmGroupService groupService,
                             SecurityService securityService, UserService userService,
                             VmInventoryService inventoryService,
                             VmMetricsService metricsService) {
        this.vmService = vmService;
        this.groupService = groupService;
        this.securityService = securityService;
        this.userService = userService;
        this.inventoryService = inventoryService;
        this.metricsService = metricsService;
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
        if (!securityService.canViewEnvironment(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        java.util.Set<String> visibleGroupIds =
                new java.util.HashSet<>(securityService.getVisibleGroupIds(environmentId));
        List<VmGroup> groups = groupService.getGroupsByEnvironmentId(environmentId).stream()
                .filter(g -> visibleGroupIds.contains(g.getGroupId()))
                .toList();
        Map<String, VmRepository.GroupVmCounts> countsByGroup = vmService.getVmCountsByGroupForEnvironment(environmentId);

        // Only the first page loads eagerly here — the rest is fetched on demand via
        // GET .../vms/{groupId}/page so a group with many VMs doesn't slow this endpoint down.
        Map<String, Page<Vm>> firstPageByGroup = new HashMap<>();
        for (VmGroup group : groups) {
            firstPageByGroup.put(group.getGroupId(), vmService.getVmsByGroupIdPaged(group.getGroupId(), 0, DEFAULT_VM_PAGE_SIZE));
        }

        // Batch-fetch private IPs once across every VM about to be returned, rather than per group.
        List<String> allVmIds = firstPageByGroup.values().stream()
                .flatMap(page -> page.getContent().stream())
                .map(Vm::getVmId)
                .toList();
        Map<String, String> privateIpsByVmId = inventoryService.getPrivateIpsByVmIds(allVmIds);

        List<VmGroupWithVmsDTO> result = new ArrayList<>();
        for (VmGroup group : groups) {
            VmRepository.GroupVmCounts counts = countsByGroup.get(group.getGroupId());
            int vmCount = counts != null ? (int) counts.getTotal() : 0;
            int runningCount = counts != null ? (int) counts.getRunning() : 0;

            Page<Vm> firstPage = firstPageByGroup.get(group.getGroupId());

            VmGroupWithVmsDTO dto = new VmGroupWithVmsDTO();
            dto.setGroup(VmGroupDTO.fromEntityWithCounts(group, vmCount, runningCount));
            dto.setVms(firstPage.getContent().stream()
                    .map(vm -> withPrivateIp(VmDTO.fromEntity(vm), privateIpsByVmId))
                    .toList());
            result.add(dto);
        }

        return ResponseEntity.ok(result);
    }

    private VmDTO withPrivateIp(VmDTO dto, Map<String, String> privateIpsByVmId) {
        dto.setPrivateIp(privateIpsByVmId.get(dto.getVmId()));
        return dto;
    }

    /** Whether the current user can see the VM's group (env-level access, or a grant on that group). */
    private boolean canSeeVm(String vmId) {
        try {
            return securityService.hasGroupAccess(vmService.getVmById(vmId).getGroup().getGroupId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @GetMapping("/{groupId}/page")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List VMs in a group, paginated",
            description = "Retrieves a page of VMs for a single group, ordered by sequence position. " +
                    "Used to page through a group's VMs beyond the first page returned by the group listing."
    )
    public ResponseEntity<Page<VmDTO>> listVmsPage(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Group ID") @PathVariable String groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        if (!securityService.canViewEnvironment(environmentId) || !securityService.hasGroupAccess(groupId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Page<Vm> vmPage = vmService.getVmsByGroupIdPaged(groupId, page, size);
        Map<String, String> privateIpsByVmId = inventoryService.getPrivateIpsByVmIds(
                vmPage.getContent().stream().map(Vm::getVmId).toList());

        Page<VmDTO> result = vmPage.map(vm -> withPrivateIp(VmDTO.fromEntity(vm), privateIpsByVmId));
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
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Vm vm = vmService.getVmById(vmId);
        Map<String, String> privateIpsByVmId = inventoryService.getPrivateIpsByVmIds(List.of(vmId));
        return ResponseEntity.ok(withPrivateIp(VmDTO.fromEntity(vm), privateIpsByVmId));
    }

    @GetMapping("/{vmId}/inventory")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VmInventoryDTO> getVmInventory(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(inventoryService.getInventory(environmentId, vmId));
    }

    @PostMapping("/{vmId}/inventory/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    public ResponseEntity<VmInventoryDTO> refreshVmInventory(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(inventoryService.refreshVmInventory(environmentId, vmId));
    }

    @GetMapping("/{vmId}/metrics")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VmMetricsDTO> getVmMetrics(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId,
            @RequestParam(defaultValue = "1h") String window,
            @RequestParam(defaultValue = "300") int period) {
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(metricsService.getMetrics(environmentId, vmId, window, period));
    }

    @GetMapping("/{vmId}/utilization-summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VmUtilizationSummaryDTO> getVmUtilizationSummary(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(metricsService.getUtilizationSummary(environmentId, vmId));
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
        Map<String, String> privateIpsByVmId = inventoryService.getPrivateIpsByVmIds(List.of(vmId));
        return ResponseEntity.ok(withPrivateIp(VmDTO.fromEntity(updated), privateIpsByVmId));
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

