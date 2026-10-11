package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.dto.WasteFindingDTO;
import com.tcgdigital.vmcontrol.dto.WasteSummaryDTO;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.service.ExcelExportService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.WasteSweepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Account-level waste found by the daily sweep (E22-T03). Admins only. */
@RestController
@RequestMapping("/api/v1/waste")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Waste", description = "Unattached volumes, unassociated IPs, gp2 volumes, long-stopped VMs and old snapshots")
public class WasteController {

    private static final int MAX_PAGE = 200;

    private final WasteSweepService sweepService;
    private final ScheduledJobLockService jobLockService;
    private final ExcelExportService excelExportService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.WasteActionService actionService;

    public WasteController(WasteSweepService sweepService, ScheduledJobLockService jobLockService,
                           ExcelExportService excelExportService, UserService userService,
                           com.tcgdigital.vmcontrol.service.WasteActionService actionService) {
        this.actionService = actionService;
        this.sweepService = sweepService;
        this.jobLockService = jobLockService;
        this.excelExportService = excelExportService;
        this.userService = userService;
    }

    public record DismissRequest(@NotBlank(message = "A reason is required") @Size(max = 500) String reason) {}

    @GetMapping("/findings")
    @Operation(summary = "Findings, most expensive first")
    public Page<WasteFindingDTO> findings(@RequestParam(required = false) WasteFindingType type,
                                          @RequestParam(required = false) String region,
                                          @RequestParam(required = false) WasteFindingStatus status,
                                          @RequestParam(required = false) String environmentId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return sweepService.findings(type, region, status, environmentId,
                Paging.of(page, size, MAX_PAGE, Sort.by(Sort.Direction.DESC, "estMonthlyCost").and(Sort.by("resourceId"))));
    }

    @GetMapping("/summary")
    @Operation(summary = "Open waste per type and in total (estimated USD per month)")
    public WasteSummaryDTO summary() {
        return sweepService.summary();
    }

    @PostMapping("/findings/{findingId}/dismiss")
    @Operation(summary = "Dismiss a finding (not waste); sweeps leave it alone")
    public WasteFindingDTO dismiss(@PathVariable String findingId, @Valid @RequestBody DismissRequest request) {
        return sweepService.dismiss(findingId, userService.getCurrentUserId(), request.reason());
    }

    @PostMapping("/findings/{findingId}/reopen")
    @Operation(summary = "Reopen a dismissed finding")
    public WasteFindingDTO reopen(@PathVariable String findingId) {
        return sweepService.reopen(findingId, userService.getCurrentUserId());
    }

    @PostMapping("/sweep")
    @Operation(summary = "Run the sweep now (409 while one runs, 503 when sweeps are off)")
    public ResponseEntity<?> sweep() {
        if (!sweepService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Waste sweeps are turned off: set waste.sweep.enabled"));
        }
        return jobLockService.tryRunLocked("waste_sweep", Duration.ofHours(1), sweepService::sweep)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("status", "busy", "message", "A waste sweep is already running")));
    }

    // ============= Fix actions (E22-T04) =============

    public record ProposeRequest(@jakarta.validation.constraints.NotNull(message = "Choose an action")
                                 com.tcgdigital.vmcontrol.model.WasteAction.Type type) {}

    @GetMapping("/actions")
    @Operation(summary = "Fix actions, newest first")
    public List<com.tcgdigital.vmcontrol.dto.WasteActionDTO> actions(
            @RequestParam(required = false) com.tcgdigital.vmcontrol.model.WasteAction.Status status) {
        return actionService.list(status);
    }

    @GetMapping("/actions/config")
    @Operation(summary = "Whether fix actions are enabled")
    public Map<String, Object> actionsConfig() {
        return Map.of("enabled", actionService.isEnabled());
    }

    @PostMapping("/findings/{findingId}/actions")
    @Operation(summary = "Propose a fix (another admin must approve it)")
    public com.tcgdigital.vmcontrol.dto.WasteActionDTO propose(@PathVariable String findingId, @Valid @RequestBody ProposeRequest request) {
        return actionService.propose(findingId, request.type(), userService.getCurrentUserId());
    }

    @PostMapping("/actions/{actionId}/approve")
    @Operation(summary = "Approve a proposed fix (not your own); it then runs in the background")
    public com.tcgdigital.vmcontrol.dto.WasteActionDTO approve(@PathVariable String actionId) {
        return actionService.approve(actionId, userService.getCurrentUserId());
    }

    @PostMapping("/actions/{actionId}/reject")
    @Operation(summary = "Reject a proposed fix")
    public com.tcgdigital.vmcontrol.dto.WasteActionDTO reject(@PathVariable String actionId) {
        return actionService.reject(actionId, userService.getCurrentUserId());
    }

    @PostMapping("/actions/{actionId}/rollback")
    @Operation(summary = "Undo an executed fix where AWS allows it")
    public com.tcgdigital.vmcontrol.dto.WasteActionDTO rollback(@PathVariable String actionId) {
        return actionService.rollback(actionId, userService.getCurrentUserId());
    }

    @GetMapping("/findings/export")
    @Operation(summary = "The matching findings as Excel")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) WasteFindingType type,
                                         @RequestParam(required = false) String region,
                                         @RequestParam(required = false) WasteFindingStatus status,
                                         @RequestParam(required = false) String environmentId) {
        byte[] workbook = excelExportService.toWorkbook("Waste", List.of("Type", "Region", "Resource", "Name", "Environment",
                "Est. monthly cost (USD)", "Status", "Waste since", "Last seen", "Dismissed because"),
                sweepService.exportRows(type, region, status, environmentId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"waste-findings.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(workbook);
    }
}
