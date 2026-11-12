package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.*;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.EksSyncService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentInsightsService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.NotificationService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Environment management operations.
 */
@RestController
@RequestMapping("/api/v1/environments")
@Tag(name = "Environments", description = "Operations for managing environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final SecurityService securityService;
    private final EksSyncService eksSyncService;
    private final EnvironmentInsightsService environmentInsightsService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.EnvironmentCostService environmentCostService;
    private final EnvironmentAccessService environmentAccessService;

    private final com.tcgdigital.vmcontrol.service.LeaseService leaseService;
    private final com.tcgdigital.vmcontrol.service.VmService vmService;
    private final com.tcgdigital.vmcontrol.service.TagReconciliationService tagReconciliationService;

    public EnvironmentController(EnvironmentService environmentService,
                                 SecurityService securityService,
                                 EksSyncService eksSyncService,
                                 EnvironmentInsightsService environmentInsightsService,
                                 NotificationService notificationService,
                                 UserService userService,
                                 EnvironmentAccessService environmentAccessService,
                                 com.tcgdigital.vmcontrol.service.EnvironmentCostService environmentCostService,
                                 com.tcgdigital.vmcontrol.service.LeaseService leaseService,
                                 com.tcgdigital.vmcontrol.service.VmService vmService,
                                 com.tcgdigital.vmcontrol.service.TagReconciliationService tagReconciliationService) {
        this.leaseService = leaseService;
        this.vmService = vmService;
        this.tagReconciliationService = tagReconciliationService;
        this.environmentAccessService = environmentAccessService;
        this.environmentCostService = environmentCostService;
        this.environmentService = environmentService;
        this.securityService = securityService;
        this.eksSyncService = eksSyncService;
        this.environmentInsightsService = environmentInsightsService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    /** Active environments missing required allocation fields (E23-T04). */
    @GetMapping("/allocation-gaps")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public java.util.List<java.util.Map<String, Object>> allocationGaps() {
        return environmentService.allocationGaps();
    }

    /** Which allocation fields are required, so forms can mark them (E23-T04). */
    @GetMapping("/allocation-rules")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public com.tcgdigital.vmcontrol.service.AllocationValidator.Rules allocationRules() {
        return allocationValidator.rules();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.AllocationValidator allocationValidator;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments",
            description = "Retrieves environments the current user has access to. Admins see all environments."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of environments",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentDTO>> listEnvironments(
            @Parameter(description = "Include inactive environments (admin only)")
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @Parameter(description = "Add groupStates: VM state counts per group, in start order (U05-T03)")
            @RequestParam(required = false, defaultValue = "false") boolean includeGroups) {

        List<Environment> environments;

        // Admins (and environment admins when their scope is global) see all environments
        if (securityService.seesAllEnvironments()) {
            environments = includeInactive
                    ? environmentService.getAllEnvironments()
                    : environmentService.getAllActiveEnvironments();
        } else {
            // Regular users only see environments they have access to
            environments = environmentService.getEnvironmentsForCurrentUser();
        }

        List<EnvironmentDTO> dtos = toDtosWithBatchedCounts(environments);
        dtos.forEach(this::withViewerFlags);
        if (includeGroups) {
            withGroupStates(dtos);
        }

        return ResponseEntity.ok(dtos);
    }

    /**
     * Fill groupStates for a page of environments (U05-T03): the counts come from one query. A
     * viewer without environment-wide view (group grants only) gets only the groups they may see,
     * and the row's VM, running and group counts are recounted from those groups.
     */
    private void withGroupStates(List<EnvironmentDTO> dtos) {
        List<String> environmentIds = dtos.stream().map(EnvironmentDTO::getEnvironmentId).toList();
        Map<String, List<String>> visible = new java.util.HashMap<>();
        for (String environmentId : environmentIds) {
            if (!securityService.hasEnvironmentWideView(environmentId)) {
                visible.put(environmentId, securityService.getVisibleGroupIds(environmentId));
            }
        }
        Map<String, List<com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO>> states =
                environmentService.getGroupStateCounts(environmentIds, visible);
        for (EnvironmentDTO dto : dtos) {
            List<com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO> groups = states.getOrDefault(dto.getEnvironmentId(), List.of());
            dto.setGroupStates(groups);
            List<String> scoped = visible.get(dto.getEnvironmentId());
            if (scoped != null) {
                dto.setGroupCount(scoped.size());
                dto.setVmCount((int) groups.stream().mapToLong(com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO::total).sum());
                dto.setRunningVmCount((int) groups.stream().mapToLong(com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO::running).sum());
            }
        }
    }

    /** What the signed-in viewer may do in the environment (U02-T02), on every DTO the app reads. */
    private EnvironmentDTO withViewerFlags(EnvironmentDTO dto) {
        String environmentId = dto.getEnvironmentId();
        dto.setViewerCanOperate(securityService.canOperateInEnvironment(environmentId));
        dto.setViewerCanAdminister(securityService.canAdministerEnvironment(environmentId));
        dto.setViewerCanManageAccess(securityService.canManageEnvironmentAccess(environmentId));
        return dto;
    }

    @GetMapping("/page")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments, paginated",
            description = "Server-side-paginated, optionally name/description-filtered variant of the " +
                    "environments list, for the \"My Environments\" table. Admins see all environments; " +
                    "regular users see only environments they have access to. state=RUNNING or STOPPED " +
                    "keeps environments with or without a running VM. Each row carries myAccess and " +
                    "monthToDateCost; the X-Total-Vms and X-Running-Vms headers count the VMs of the whole " +
                    "filtered set (the environment count is page.totalElements)."
    )
    public ResponseEntity<Page<EnvironmentDTO>> listEnvironmentsPaged(
            @Parameter(description = "Include inactive environments (admin only)")
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @Parameter(description = "Optional name/description search filter")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Add groupStates: VM state counts per group, in start order (U05-T03)")
            @RequestParam(required = false, defaultValue = "false") boolean includeGroups,
            @Parameter(description = "RUNNING: environments with a running VM; STOPPED: those without one (U06-T04)")
            @RequestParam(required = false) String state,
            @Parameter(description = "Only environments with a VM in this AWS region (U19-T08)")
            @RequestParam(required = false) String region,
            @Parameter(description = "Order: name (default), running (most VMs running first) or cost (highest month to date first) (U22-T13)")
            @RequestParam(required = false, defaultValue = "name") String sort) {
        Pageable pageable = com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size,
                com.tcgdigital.vmcontrol.controller.support.Paging.DEFAULT_MAX_SIZE); // 400 on page < 0, size clamped
        Boolean running = runningFilter(state);
        if ("running".equals(sort) || "cost".equals(sort)) {
            // U22-T13: ordered across the whole filtered set, then paged.
            List<Environment> all = pageOfEnvironments(includeInactive, search, running, region, Pageable.unpaged()).getContent();
            List<EnvironmentDTO> rows = fleetRows(all, includeGroups);
            java.util.Comparator<EnvironmentDTO> by = "running".equals(sort)
                    ? java.util.Comparator.comparingInt(EnvironmentDTO::getRunningVmCount).reversed()
                    : java.util.Comparator.comparing((EnvironmentDTO d) -> d.getMonthToDateCost() == null
                            ? java.math.BigDecimal.valueOf(-1) : d.getMonthToDateCost()).reversed();
            rows.sort(by.thenComparing(d -> String.valueOf(d.getDisplayName() != null ? d.getDisplayName() : d.getName()),
                    String.CASE_INSENSITIVE_ORDER));
            int from = (int) Math.min(pageable.getOffset(), rows.size());
            int to = Math.min(from + pageable.getPageSize(), rows.size());
            long[] totals = fleetTotals(all);
            return ResponseEntity.ok()
                    .header("X-Total-Vms", String.valueOf(totals[0]))
                    .header("X-Running-Vms", String.valueOf(totals[1]))
                    .body(new org.springframework.data.domain.PageImpl<>(rows.subList(from, to), pageable, rows.size()));
        }
        Page<Environment> environments = pageOfEnvironments(includeInactive, search, running, region, pageable);

        List<String> environmentIds = environments.getContent().stream()
                .map(Environment::getEnvironmentId)
                .toList();
        Map<String, EnvironmentService.EnvironmentCounts> counts = environmentService.getBatchCounts(environmentIds);
        Map<String, EnvironmentService.LockSummary> locks = environmentService.getLockSummaries(environmentIds);

        Map<String, String> accountNames = accountNames();
        Page<EnvironmentDTO> dtos = environments.map(env -> withViewerFlags(
                withAccountName(toDtoWithCounts(env, counts, locks), accountNames)));
        if (includeGroups) {
            withGroupStates(dtos.getContent());
        }
        withAccessAndCost(environments.getContent(), dtos.getContent());

        // The header line counts the whole filtered set, not this page (U06-T04).
        List<Environment> all = environments.getTotalPages() <= 1
                ? environments.getContent()
                : pageOfEnvironments(includeInactive, search, running, region, Pageable.unpaged()).getContent();
        long[] totals = fleetTotals(all);
        return ResponseEntity.ok()
                .header("X-Total-Vms", String.valueOf(totals[0]))
                .header("X-Running-Vms", String.valueOf(totals[1]))
                .body(dtos);
    }

    private Page<Environment> pageOfEnvironments(boolean includeInactive, String search, Boolean running,
                                                 String region, Pageable pageable) {
        if (region != null && !region.isBlank() && !com.tcgdigital.vmcontrol.service.aws.AwsRegions.isRegionCode(region)) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException("region must be an AWS region code, such as eu-west-1");
        }
        return securityService.seesAllEnvironments()
                ? (includeInactive
                        ? environmentService.getAllEnvironments(search, running, region, pageable)
                        : environmentService.getAllActiveEnvironments(search, running, region, pageable))
                : environmentService.getEnvironmentsForCurrentUser(search, running, region, pageable);
    }

    @GetMapping("/regions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Regions with VMs (U19-T08)",
            description = "The AWS regions that hold at least one active VM, for the list's region filter. Region codes only.")
    public ResponseEntity<List<String>> regionsInUse() {
        return ResponseEntity.ok(environmentService.regionsInUse());
    }

    /** state=RUNNING → true, STOPPED → false, absent → null (all); anything else is a 400. */
    private static Boolean runningFilter(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return switch (state.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "RUNNING" -> Boolean.TRUE;
            case "STOPPED" -> Boolean.FALSE;
            default -> throw new com.tcgdigital.vmcontrol.exception.ValidationException(
                    "state must be one of RUNNING, STOPPED");
        };
    }

    /**
     * "Your access" and "This month" for a page of rows (U06-T04), from one cost call: the
     * viewer's environment-wide level and the month-to-date estimate (none for group-only grants).
     */
    private void withAccessAndCost(List<Environment> environments, List<EnvironmentDTO> dtos) {
        if (environments.isEmpty()) {
            return;
        }
        Map<String, MyEnvironmentCostDTO> rows = environmentCostService.getCostRowsFor(
                userService.getCurrentUser(), environments);
        for (EnvironmentDTO dto : dtos) {
            MyEnvironmentCostDTO row = rows.get(dto.getEnvironmentId());
            if (row != null) {
                dto.setMyAccess(row.myLevel());
                dto.setMonthToDateCost(row.monthToDateEstimated());
                dto.setScheduleRuleCount(row.scheduleRuleCount());
                dto.setNextScheduledStop(row.nextStop());
            }
        }
    }

    /** Every row of a filtered list, as the paged list builds them (U22-T13: sorting and export). */
    private List<EnvironmentDTO> fleetRows(List<Environment> environments, boolean includeGroups) {
        List<String> ids = environments.stream().map(Environment::getEnvironmentId).toList();
        Map<String, EnvironmentService.EnvironmentCounts> counts = environmentService.getBatchCounts(ids);
        Map<String, EnvironmentService.LockSummary> locks = environmentService.getLockSummaries(ids);
        Map<String, String> accountNames = accountNames();
        List<EnvironmentDTO> dtos = new java.util.ArrayList<>(environments.stream()
                .map(env -> withViewerFlags(withAccountName(toDtoWithCounts(env, counts, locks), accountNames)))
                .toList());
        if (includeGroups) {
            withGroupStates(dtos);
        }
        withAccessAndCost(environments, dtos);
        return dtos;
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The environments list as CSV (U22-T13)",
            description = "Every environment the caller can see, with the list's filters: name, region, team, VMs running and in all, "
                    + "lock, your access, cost this month (estimate) and the next scheduled stop.")
    public ResponseEntity<String> exportEnvironments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String region) {
        List<Environment> all = pageOfEnvironments(false, search, runningFilter(state), region, Pageable.unpaged()).getContent();
        List<EnvironmentDTO> rows = fleetRows(all, false);
        rows.sort(java.util.Comparator.comparing(d -> String.valueOf(d.getDisplayName() != null ? d.getDisplayName() : d.getName()),
                String.CASE_INSENSITIVE_ORDER));
        StringBuilder csv = new StringBuilder("Environment,Short name,Region,Team,VMs running,VMs,Locked,Your access,Cost this month (USD),Next scheduled stop (UTC)\r\n");
        for (EnvironmentDTO d : rows) {
            csv.append(String.join(",",
                    csvCell(d.getDisplayName()), csvCell(d.getName()), csvCell(firstRegion(d)), csvCell(d.getTeamName()),
                    String.valueOf(d.getRunningVmCount()), String.valueOf(d.getVmCount()),
                    d.isLocked() ? "yes" : "no",
                    csvCell(d.getMyAccess()),
                    d.getMonthToDateCost() == null ? "" : d.getMonthToDateCost().toPlainString(),
                    d.getNextScheduledStop() == null ? "" : d.getNextScheduledStop().toString())).append("\r\n");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"environments-" + java.time.LocalDate.now() + ".csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString());
    }

    private static String firstRegion(EnvironmentDTO d) {
        return d.getRegions() == null || d.getRegions().isEmpty() ? null : String.join(" ", d.getRegions());
    }

    /** A CSV cell; text that a spreadsheet would run as a formula is prefixed with a quote. */
    static String csvCell(String value) {
        if (value == null) return "";
        String v = value.matches("^[=+\\-@].*") ? "'" + value : value;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /**
     * {VMs, running VMs} across the given environments; a group-scoped viewer counts only the
     * groups they can see, as in their rows.
     */
    private long[] fleetTotals(List<Environment> environments) {
        List<String> ids = environments.stream().map(Environment::getEnvironmentId).toList();
        if (ids.isEmpty()) {
            return new long[] {0, 0};
        }
        Map<String, List<String>> visible = new java.util.HashMap<>();
        for (String id : ids) {
            if (!securityService.hasEnvironmentWideView(id)) {
                visible.put(id, securityService.getVisibleGroupIds(id));
            }
        }
        List<String> wide = ids.stream().filter(id -> !visible.containsKey(id)).toList();
        Map<String, EnvironmentService.EnvironmentCounts> counts = wide.isEmpty()
                ? Map.of() : environmentService.getBatchCounts(wide);
        long vms = 0;
        long running = 0;
        for (EnvironmentService.EnvironmentCounts c : counts.values()) {
            vms += c.vmCount();
            running += c.runningVmCount();
        }
        if (!visible.isEmpty()) {
            Map<String, List<GroupStateCountsDTO>> states =
                    environmentService.getGroupStateCounts(List.copyOf(visible.keySet()), visible);
            for (List<GroupStateCountsDTO> groups : states.values()) {
                for (GroupStateCountsDTO g : groups) {
                    vms += g.total();
                    running += g.running();
                }
            }
        }
        return new long[] {vms, running};
    }

    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List environments available for access request",
            description = "Retrieves active environments the current user does NOT have access to. Used for Request Access feature."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of available environments",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = EnvironmentDTO.class))
                    )
            )
    })
    public ResponseEntity<List<EnvironmentDTO>> listAvailableEnvironments() {
        // U22-T08: someone whose role covers every environment has nothing to ask for (staging offered an
        // administrator all 162 environments they already administer).
        if (securityService.seesAllEnvironments()) {
            return ResponseEntity.ok(List.of());
        }
        List<Environment> environments = environmentService.getEnvironmentsWithoutAccessForCurrentUser();

        List<EnvironmentDTO> dtos = toDtosWithBatchedCounts(environments);

        return ResponseEntity.ok(dtos);
    }

    /**
     * Attaches group/VM/running/region counts to a batch of environments with a small, fixed
     * number of queries total, regardless of list size — avoids issuing them per environment
     * (see EnvironmentService.getBatchCounts). Shared by every environment-listing endpoint
     * (list, paged, available) so they can't drift out of sync with each other.
     */
    private List<EnvironmentDTO> toDtosWithBatchedCounts(List<Environment> environments) {
        List<String> environmentIds = environments.stream().map(Environment::getEnvironmentId).toList();
        var counts = environmentService.getBatchCounts(environmentIds);
        var locks = environmentService.getLockSummaries(environmentIds);

        Map<String, String> accountNames = accountNames();
        return environments.stream()
                .map(env -> withAccountName(toDtoWithCounts(env, counts, locks), accountNames))
                .toList();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.aws.AwsAccountService awsAccountService;

    private Map<String, String> accountNames() {
        return awsAccountService == null ? Map.of() : awsAccountService.namesByRef();
    }

    /** The account's display name (E28-T10), from one lookup per request. */
    private static EnvironmentDTO withAccountName(EnvironmentDTO dto, Map<String, String> names) {
        if (dto.getAwsAccountRef() != null) {
            dto.setAwsAccountName(names.get(dto.getAwsAccountRef()));
        }
        return dto;
    }

    private EnvironmentDTO toDtoWithCounts(Environment env, Map<String, EnvironmentService.EnvironmentCounts> counts,
                                           Map<String, EnvironmentService.LockSummary> locks) {
        var c = counts.get(env.getEnvironmentId());
        EnvironmentDTO dto = EnvironmentDTO.fromEntityWithCounts(env, c.groupCount(), c.vmCount(), c.runningVmCount(), c.regions());
        EnvironmentService.LockSummary lock = locks.get(env.getEnvironmentId());
        if (lock != null) {
            dto.setLocked(true);
            dto.setLockedByUserId(lock.lockedByUserId());
            dto.setLockedByDisplayName(lock.lockedByDisplayName());
            dto.setLockedAt(lock.lockedAt());
        }
        return dto;
    }

    @GetMapping("/{environmentId}/cost")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Environment cost for its users",
            description = "Month to date, trend, run-rate forecast, top VMs, savings and the next scheduled stop/start. "
                    + "Anyone who can view the environment; a group-only grant sees its groups only (scope GROUPS)."
    )
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.EnvironmentCostDTO> getEnvironmentCost(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanView(environmentId); // 404 without access: no cost data leaks
        return ResponseEntity.ok(environmentCostService.getCost(environmentId));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.EksWorkloadCostService eksWorkloadCostService;

    @GetMapping("/{environmentId}/eks-workload-cost")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "EKS namespace and workload cost",
            description = "Split Cost Allocation Data for the last 1-90 days (E26-T05). Anyone who views the whole "
                    + "environment; a group-only grant is refused, since namespaces span every node group."
    )
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.EksWorkloadCostDTO> getEksWorkloadCost(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @RequestParam(defaultValue = "7") int days) {
        securityService.assertCanView(environmentId);
        if (!securityService.hasEnvironmentWideView(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.UnauthorizedException(
                    "Workload cost needs access to the whole environment");
        }
        return ResponseEntity.ok(eksWorkloadCostService.forEnvironment(environmentId, days));
    }

    @GetMapping("/{environmentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get environment details",
            description = "Retrieves detailed information about a specific environment. User must have access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved environment",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> getEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        // Check access
        if (!securityService.canViewEnvironment(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Environment environment = environmentService.getEnvironmentById(environmentId);
        EnvironmentDTO dto = toDtosWithBatchedCounts(List.of(environment)).get(0);
        if (leaseService.isFeatureEnabled()) {
            dto.setLeaseEnabled(Boolean.TRUE.equals(environment.getLeaseEnabled()));
        }
        withViewerFlags(dto);
        withViewerGrant(dto);

        return ResponseEntity.ok(dto);
    }

    /** The viewer's own level and access end (U07-T01); none for administrators. */
    private void withViewerGrant(EnvironmentDTO dto) {
        if (securityService.isAdmin()) {
            return;
        }
        environmentAccessService.effectiveGrant(userService.getCurrentUserId(), dto.getEnvironmentId())
                .ifPresent(grant -> {
                    dto.setViewerAccessLevel(grant.level().name());
                    dto.setViewerAccessEndsAt(grant.endsAt());
                });
    }

    /**
     * An environment by name (E34-T08: the Terraform provider's data source and import). 404 both
     * when there is none and when the caller may not see it, so names do not leak.
     */
    @GetMapping("/by-name/{name}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get an environment by name", description = "404 when it does not exist or you cannot see it.")
    public ResponseEntity<EnvironmentDTO> getEnvironmentByName(@PathVariable String name) {
        Environment environment = environmentService.findByName(name)
                .filter(e -> securityService.canViewEnvironment(e.getEnvironmentId()))
                .orElseThrow(() -> new com.tcgdigital.vmcontrol.exception.ResourceNotFoundException("Environment", name));
        EnvironmentDTO dto = toDtosWithBatchedCounts(List.of(environment)).get(0);
        if (leaseService.isFeatureEnabled()) {
            dto.setLeaseEnabled(Boolean.TRUE.equals(environment.getLeaseEnabled()));
        }
        return ResponseEntity.ok(withViewerFlags(dto));
    }

    @GetMapping("/{environmentId}/insights")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get environment insights",
            description = "Returns aggregate inventory, utilization, storage, idle, and recommendation data for an environment."
    )
    public ResponseEntity<EnvironmentInsightsDTO> getEnvironmentInsights(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "24h (default) or 7d: the window of averages and busiest VMs")
            @RequestParam(defaultValue = "24h") String window) {
        if (!securityService.canViewEnvironment(environmentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            EnvironmentInsightsService.parseWindow(window);
        } catch (IllegalArgumentException e) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException(e.getMessage());
        }
        // Restrict to the caller's visible groups — a no-op for anyone with environment-wide
        // access (getVisibleGroupIds returns every group for them).
        return ResponseEntity.ok(environmentInsightsService.getInsights(
                environmentId, securityService.getVisibleGroupIds(environmentId), window));
    }

    @GetMapping("/discover/eks")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Discover unregistered EKS clusters",
            description = "Returns EKS clusters that exist in AWS but are not yet registered as environments, with their region"
    )
    public ResponseEntity<List<EksClusterInfoDTO>> discoverEksClusters() {
        return ResponseEntity.ok(eksSyncService.getUnregisteredEksClusters());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Create a new environment",
            description = "Creates a new environment for organizing VMs"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Environment created successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid input or duplicate name")
    })
    public ResponseEntity<EnvironmentDTO> createEnvironment(
            @Valid @RequestBody CreateEnvironmentDTO dto) {

        Environment created = environmentService.createEnvironment(dto, userService.getCurrentUserId());
        grantCreatorAdmin(created);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnvironmentDTO.fromEntity(created));
    }

    /**
     * A non-global admin who creates an environment gets an ENVIRONMENT ADMIN grant on it, so they
     * keep administering it when security.env-admin.scope=assigned (a new environment has no grants).
     */
    private void grantCreatorAdmin(Environment created) {
        User creator = userService.getCurrentUser();
        if (creator == null || creator.isAdmin() || creator.getEmail() == null) {
            return;
        }
        AccessGrantRequestDTO grant = new AccessGrantRequestDTO();
        grant.setUserEmail(creator.getEmail());
        grant.setEnvironmentId(created.getEnvironmentId());
        grant.setAccessLevel(AccessLevel.ADMIN);
        grant.setScopeType(AccessScopeType.ENVIRONMENT);
        grant.setNotes("Created the environment");
        environmentAccessService.grantScoped(creator.getUserId(), grant);
    }

    @PutMapping("/{environmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Update an environment",
            description = "Updates an existing environment's details"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Environment updated successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> updateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Valid @RequestBody UpdateEnvironmentDTO dto) {
        securityService.assertCanAdminister(environmentId);

        Environment updated = environmentService.updateEnvironment(environmentId, dto, userService.getCurrentUserId());
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(updated));
    }

    @DeleteMapping("/{environmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Deactivate an environment",
            description = "Soft deletes an environment by marking it as inactive; its active lock is released. "
                    + "Refused while an operation is running. It can be reactivated later."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Environment deactivated successfully"),
            @ApiResponse(responseCode = "400", description = "An operation is running in the environment"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Void> deactivateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);

        environmentService.deactivateEnvironment(environmentId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{environmentId}/reactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Reactivate an environment",
            description = "Reverses a soft delete, making a deactivated environment active again"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Environment reactivated successfully",
                    content = @Content(schema = @Schema(implementation = EnvironmentDTO.class))
            ),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<EnvironmentDTO> reactivateEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);

        Environment reactivated = environmentService.reactivateEnvironment(environmentId, userService.getCurrentUserId());
        return ResponseEntity.ok(EnvironmentDTO.fromEntity(reactivated));
    }

    @GetMapping("/{environmentId}/notify-stop/audience")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "How many people the stop notice reaches (U11-T02)",
            description = "The number of people notify-stop would email: everyone with active access to the whole "
                    + "environment, administrators and the environment admins who look after it, with an email address.")
    public ResponseEntity<Map<String, Integer>> notifyStopAudience(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);
        environmentService.getEnvironmentById(environmentId);
        return ResponseEntity.ok(Map.of("count", notificationService.countStopNoticeAudience(environmentId)));
    }

    @GetMapping("/{environmentId}/cost-tags")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "The cost-allocation tags on every VM of the environment (U11-T06)",
            description = "The tags the platform puts on the environment's VMs for cost allocation (managed-by, "
                    + "environment, team and the team's cost center), and whether tagging is switched on.")
    public ResponseEntity<Map<String, Object>> costTags(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        securityService.assertCanAdminister(environmentId);
        Environment environment = environmentService.getEnvironmentById(environmentId);
        List<Map<String, String>> tags = tagReconciliationService.tagsFor(environment).entrySet().stream()
                .map(e -> Map.of("key", e.getKey(), "value", e.getValue() == null ? "" : e.getValue()))
                .toList();
        return ResponseEntity.ok(Map.of("enabled", tagReconciliationService.isTaggingEnabled(), "tags", tags));
    }

    @GetMapping("/review-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "VMs that need a look, per environment (U11-T02)",
            description = "For each environment the caller administers that has any: drifted VMs (cloud state differs) "
                    + "and VMs found by discovery that wait for a group.")
    public ResponseEntity<List<com.tcgdigital.vmcontrol.dto.RegistryReviewSummaryDTO>> reviewSummary() {
        return ResponseEntity.ok(vmService.getReviewSummary(securityService.administeredEnvironmentIdsOrAll()));
    }

    @PostMapping("/{environmentId}/notify-stop")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Notify environment members that it is being stopped",
            description = "Emails (and bell-notifies) everyone with active access to this environment. "
                    + "An explicit admin action, independent of notification.email.* flags — the click is the opt-in."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notified — body reports how many people were emailed"),
            @ApiResponse(responseCode = "404", description = "Environment not found")
    })
    public ResponseEntity<Map<String, Object>> notifyStop(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @RequestBody(required = false) Map<String, String> body) {
        securityService.assertCanAdminister(environmentId);

        Environment environment = environmentService.getEnvironmentById(environmentId);
        String reason = body != null ? body.get("reason") : null;
        String actorUserId = userService.getCurrentUserId();
        int emailed = notificationService.notifyStopEnvironment(
                environmentId, environment.getDisplayName(), actorUserId, reason);
        return ResponseEntity.ok(Map.of("emailedCount", emailed));
    }
}
