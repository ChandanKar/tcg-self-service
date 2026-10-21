package com.tcgdigital.vmcontrol.controller;

import org.springframework.data.domain.PageRequest;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.dto.MoveVmDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.VmInventoryDTO;
import com.tcgdigital.vmcontrol.dto.VmMetricsDTO;
import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.dto.VmGroupDTO;
import com.tcgdigital.vmcontrol.dto.VmUtilizationSummaryDTO;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.VmGroupService;
import com.tcgdigital.vmcontrol.service.VmInventoryService;
import com.tcgdigital.vmcontrol.service.VmListDetailsService;
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
    private final VmListDetailsService detailsService;
    private final EnvironmentService environmentService;

    private final com.tcgdigital.vmcontrol.service.VmAdviceService adviceService;
    private final com.tcgdigital.vmcontrol.service.VmCandidateService candidateService;

    public VmMgmtController(VmService vmService, VmGroupService groupService,
                             SecurityService securityService, UserService userService,
                             VmInventoryService inventoryService,
                             VmMetricsService metricsService,
                             VmListDetailsService detailsService,
                             EnvironmentService environmentService,
                             com.tcgdigital.vmcontrol.service.VmAdviceService adviceService,
                             com.tcgdigital.vmcontrol.service.VmCandidateService candidateService) {
        this.vmService = vmService;
        this.groupService = groupService;
        this.securityService = securityService;
        this.userService = userService;
        this.inventoryService = inventoryService;
        this.metricsService = metricsService;
        this.detailsService = detailsService;
        this.environmentService = environmentService;
        this.adviceService = adviceService;
        this.candidateService = candidateService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all VMs in an environment",
            description = "Retrieves a list of all VMs in the specified environment, grouped by their VM groups. " +
                    "Requires access to the environment. Each VM's stateSince is when it entered its current " +
                    "status; it is null when the state history has no matching row (registered while already in " +
                    "that state, or removed by data retention)."
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
        // Each group's VMs by state (running, starting or stopping, stopped, failed, not read): one query.
        Map<String, com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO> statesByGroup = new HashMap<>();
        environmentService.getGroupStateCounts(List.of(environmentId), null)
                .getOrDefault(environmentId, List.of())
                .forEach(states -> statesByGroup.put(states.groupId(), states));

        // Only the first page loads eagerly here — the rest is fetched on demand via
        // GET .../vms/{groupId}/page so a group with many VMs doesn't slow this endpoint down.
        Map<String, Page<Vm>> firstPageByGroup = new HashMap<>();
        for (VmGroup group : groups) {
            firstPageByGroup.put(group.getGroupId(), vmService.getVmsByGroupIdPaged(group.getGroupId(), 0, DEFAULT_VM_PAGE_SIZE));
        }

        // Private IPs and "in this state since" for every VM about to be returned: one batch, not per group.
        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(firstPageByGroup.values().stream()
                .flatMap(page -> page.getContent().stream())
                .toList());

        // U02-T02: Start/Stop per group. Environment-level operators (and admins) get every group; an
        // operator of one group only gets that one.
        com.tcgdigital.vmcontrol.model.AccessLevel envLevel = securityService.effectiveEnvLevel(environmentId);
        boolean environmentOperator = securityService.canAdministerEnvironment(environmentId)
                || (envLevel != null && envLevel != com.tcgdigital.vmcontrol.model.AccessLevel.VIEWER);
        List<VmGroupWithVmsDTO> result = new ArrayList<>();
        for (VmGroup group : groups) {
            Page<Vm> firstPage = firstPageByGroup.get(group.getGroupId());

            VmGroupWithVmsDTO dto = new VmGroupWithVmsDTO();
            dto.setGroup(VmGroupDTO.fromEntityWithStates(group, statesByGroup.get(group.getGroupId())));
            dto.setViewerCanOperate(environmentOperator
                    || securityService.hasGroupAccessLevel(group.getGroupId(), com.tcgdigital.vmcontrol.model.AccessLevel.USER));
            dto.setVms(firstPage.getContent().stream()
                    .map(vm -> VmListDetailsService.apply(VmDTO.fromEntity(vm), details))
                    .toList());
            result.add(dto);
        }

        return ResponseEntity.ok(result);
    }

    /** The environment a group belongs to (404 when the group does not exist). */
    private String envOfGroup(String groupId) {
        return groupService.getGroupById(groupId).getEnvironment().getEnvironmentId();
    }

    /** The environment a VM belongs to (404 when the VM does not exist). */
    private String envOfVm(String vmId) {
        return vmService.getVmById(vmId).getGroup().getEnvironment().getEnvironmentId();
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
            @RequestParam(defaultValue = "25") int size,
            @Parameter(description = "Only VMs in this state: running, stopped or problems (error, not found, "
                    + "terminated, or drift flagged); all when left out")
            @RequestParam(required = false) String state) {
        securityService.assertSameEnvironment(envOfGroup(groupId), environmentId);
        PageRequest pageable = Paging.of(page, size, Paging.DEFAULT_MAX_SIZE); // 400 on page < 0, size clamped


        if (!securityService.canViewEnvironment(environmentId) || !securityService.hasGroupAccess(groupId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Page<Vm> vmPage = vmService.getVmsByGroupIdPaged(groupId, state, pageable);
        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(vmPage.getContent());

        Page<VmDTO> result = vmPage.map(vm -> VmListDetailsService.apply(VmDTO.fromEntity(vm), details));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Registry review list",
            description = "VMs of the environment that need attention: state=DRIFT (drift flagged), PENDING "
                    + "(discovered, not yet reviewed) or INACTIVE, most recently changed first."
    )
    public ResponseEntity<Page<VmDTO>> reviewList(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @RequestParam String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        securityService.assertCanView(environmentId);
        String normalized = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if (!REVIEW_STATES.contains(normalized)) {
            throw new ValidationException("state must be one of DRIFT, PENDING, INACTIVE");
        }
        Page<Vm> vms = vmService.getReviewPage(environmentId, normalized, Paging.of(page, size, Paging.DEFAULT_MAX_SIZE));
        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(vms.getContent());
        return ResponseEntity.ok(vms.map(vm -> VmListDetailsService.apply(VmDTO.fromEntity(vm), details)));
    }

    private static final java.util.Set<String> REVIEW_STATES = java.util.Set.of("DRIFT", "PENDING", "INACTIVE");

    @GetMapping("/review/counts")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Registry review counts", description = "Counts of drifted, pending-review and inactive VMs.")
    public ResponseEntity<Map<String, Long>> reviewCounts(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanView(environmentId);
        return ResponseEntity.ok(vmService.getReviewCounts(environmentId));
    }

    @PutMapping("/{vmId}/move")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Move a VM to another group",
            description = "Moves a VM (e.g. out of Auto-Discovered) to another group of the same environment; "
                    + "clears its dependencies and the pending-review flag."
    )
    public ResponseEntity<VmDTO> moveVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId,
            @Valid @RequestBody MoveVmDTO dto) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        securityService.assertCanAdminister(environmentId);

        Vm vm = vmService.moveVm(vmId, dto, userService.getCurrentUserId());
        return ResponseEntity.ok(detailsService.toDto(vm));
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
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);


        // Check access
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Vm vm = vmService.getVmById(vmId);
        return ResponseEntity.ok(detailsService.toDto(vm));
    }

    /**
     * What the VM costs this month and the size the platform recommends for it (U08-T06), for
     * everyone who can see the VM; the same access checks as {@link #getVm}.
     */
    @GetMapping("/{vmId}/advice")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a VM's cost this month and rightsizing advice")
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.VmAdviceDTO> getVmAdvice(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        if (!securityService.canViewEnvironment(environmentId) || !canSeeVm(vmId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(adviceService.advice(vmId));
    }

    @GetMapping("/{vmId}/inventory")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VmInventoryDTO> getVmInventory(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);

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
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        securityService.assertCanAdminister(environmentId);

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
            @Parameter(description = "Point spacing in seconds: 300, 900 or 3600 (anything else is a 400)")
            @RequestParam(defaultValue = "300") int period) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);

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
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);

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
        securityService.assertSameEnvironment(envOfGroup(dto.getGroupId()), environmentId);
        securityService.assertCanAdminister(environmentId);


        Vm created = vmService.registerVm(dto, userService.getCurrentUserId(), userService.isCurrentUserAdmin());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(detailsService.toDto(created));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Instances that can be added to the environment (U11-T01)",
            description = "The EC2 instances (source=EC2) or RDS databases (source=RDS) of the account and region, each "
                    + "with an opaque reference to add it by, its name, size, zone, IP and state, and what it is to this "
                    + "environment: AVAILABLE, DISCOVERED_UNGROUPED (found by discovery here; adding moves it), "
                    + "IN_ENVIRONMENT, STALE or REMOVED. Cloud ids are included for administrators only."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The candidates",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = com.tcgdigital.vmcontrol.dto.VmCandidateDTO.class)))),
            @ApiResponse(responseCode = "403", description = "Not an admin of this environment"),
            @ApiResponse(responseCode = "404", description = "Environment not found, or RDS support is off")
    })
    public ResponseEntity<List<com.tcgdigital.vmcontrol.dto.VmCandidateDTO>> listCandidates(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "AWS region", example = "ap-south-1") @RequestParam String region,
            @Parameter(description = "AWS account ref; the default account when omitted") @RequestParam(required = false) String accountRef,
            @Parameter(description = "EC2 (default) or RDS") @RequestParam(defaultValue = "EC2")
            com.tcgdigital.vmcontrol.service.VmCandidateService.Source source) {
        securityService.assertCanAdminister(environmentId);
        return ResponseEntity.ok(candidateService.listCandidates(environmentId, source, region, accountRef,
                userService.getCurrentUserId()));
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Add several instances to a group (U11-T01)",
            description = "Registers the picked candidates (by reference) in one group of the environment, or in a new "
                    + "group made at the end of the start order, after the group's current VMs. A VM found by discovery "
                    + "in this environment is moved there instead. All or nothing: an instance already in an environment "
                    + "refuses the batch, naming it."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The VMs added",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = VmDTO.class)))),
            @ApiResponse(responseCode = "400", description = "An instance is already registered, a reference is out of date, or the input is invalid"),
            @ApiResponse(responseCode = "403", description = "Not an admin of this environment")
    })
    public ResponseEntity<List<VmDTO>> registerBatch(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody com.tcgdigital.vmcontrol.dto.BatchRegisterVmsDTO dto) {
        securityService.assertCanAdminister(environmentId);
        List<Vm> added = candidateService.registerBatch(environmentId, dto, userService.getCurrentUserId());
        candidateService.tagNewVmsSafely(environmentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(added.stream().map(detailsService::toDto).toList());
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
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        if (dto.getGroupId() != null) {
            // Moving a VM is only allowed within the same environment.
            securityService.assertSameEnvironment(envOfGroup(dto.getGroupId()), environmentId);
        }
        securityService.assertCanAdminister(environmentId);


        Vm updated = vmService.updateVm(vmId, dto, userService.getCurrentUserId(), userService.isCurrentUserAdmin());
        return ResponseEntity.ok(detailsService.toDto(updated));
    }

    @DeleteMapping("/{vmId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Delete a VM",
            description = "Removes a VM from the platform; history is kept and discovery ignores the instance "
                    + "(does not affect the actual cloud VM). Reactivate it to bring it back."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "VM deleted successfully"),
            @ApiResponse(responseCode = "400", description = "VM has dependents and cannot be deleted"),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<Void> deleteVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        securityService.assertCanAdminister(environmentId);


        vmService.deleteVm(vmId, userService.getCurrentUserId());
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
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        securityService.assertCanAdminister(environmentId);


        String userId = userService.getCurrentUserId();
        Vm vm = vmService.acknowledgeVm(vmId, userId);
        return ResponseEntity.ok(detailsService.toDto(vm));
    }

    @PostMapping("/{vmId}/reactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Reactivate a deactivated VM",
            description = "Brings back a VM that state sync deactivated (instance not found), once the "
                    + "instance exists again in its region; the VM gets the status the cloud reports."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "VM reactivated"),
            @ApiResponse(responseCode = "400", description = "VM is active, or the instance still cannot be found"),
            @ApiResponse(responseCode = "404", description = "VM not found")
    })
    public ResponseEntity<VmDTO> reactivateVm(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "VM ID") @PathVariable String vmId) {
        securityService.assertSameEnvironment(envOfVm(vmId), environmentId);
        securityService.assertCanAdminister(environmentId);

        Vm vm = vmService.reactivateVm(vmId, userService.getCurrentUserId());
        return ResponseEntity.ok(detailsService.toDto(vm));
    }

    /**
     * DTO for VM list grouped by groups.
     */
    public static class VmGroupWithVmsDTO {
        private VmGroupDTO group;
        private List<VmDTO> vms;
        /** The signed-in viewer may start and stop this group's VMs (U02-T02). */
        private boolean viewerCanOperate;

        public boolean isViewerCanOperate() {
            return viewerCanOperate;
        }

        public void setViewerCanOperate(boolean viewerCanOperate) {
            this.viewerCanOperate = viewerCanOperate;
        }

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

