package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.dto.VmStateHistoryDTO;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for monitoring and state sync operations.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Monitoring", description = "VM state monitoring and drift detection")
public class MonitoringController {

    private final StateSyncService stateSyncService;

    public MonitoringController(StateSyncService stateSyncService) {
        this.stateSyncService = stateSyncService;
    }

    @GetMapping("/sync-status")
    @Operation(
            summary = "Get sync status",
            description = "Returns the current status of VM state synchronization"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Sync status retrieved successfully",
                    content = @Content(schema = @Schema(implementation = StateSyncStatusDTO.class))
            )
    })
    public ResponseEntity<StateSyncStatusDTO> getSyncStatus() {
        StateSyncStatusDTO status = stateSyncService.getSyncStatus();
        return ResponseEntity.ok(status);
    }

    @PostMapping("/sync")
    @Operation(
            summary = "Trigger manual sync",
            description = "Triggers a manual VM state synchronization"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sync completed"),
            @ApiResponse(responseCode = "409", description = "Sync already in progress")
    })
    public ResponseEntity<StateSyncStatusDTO> triggerSync() {
        if (stateSyncService.isSyncInProgress()) {
            return ResponseEntity.status(409).body(stateSyncService.getSyncStatus());
        }

        StateSyncStatusDTO result = stateSyncService.syncAllVmStates();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/environment/{environmentId}")
    @Operation(
            summary = "Sync environment VMs",
            description = "Triggers VM state sync for a specific environment"
    )
    public ResponseEntity<Map<String, Object>> syncEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {

        int driftCount = stateSyncService.syncEnvironmentVmStates(environmentId);

        return ResponseEntity.ok(Map.of(
                "environmentId", environmentId,
                "driftDetected", driftCount,
                "status", "completed"
        ));
    }

    @GetMapping("/vms/{vmId}/history")
    @Operation(
            summary = "Get VM state history",
            description = "Returns the state change history for a specific VM"
    )
    public ResponseEntity<Page<VmStateHistoryDTO>> getVmStateHistory(
            @Parameter(description = "VM ID") @PathVariable String vmId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<VmStateHistory> history = stateSyncService.getVmStateHistory(vmId, page, size);
        Page<VmStateHistoryDTO> dtos = history.map(VmStateHistoryDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/state-changes")
    @Operation(
            summary = "Get recent state changes",
            description = "Returns recent VM state changes across all VMs"
    )
    public ResponseEntity<List<VmStateHistoryDTO>> getRecentStateChanges() {
        List<VmStateHistory> changes = stateSyncService.getRecentStateChanges();
        List<VmStateHistoryDTO> dtos = changes.stream()
                .map(VmStateHistoryDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/drift-events")
    @Operation(
            summary = "Get drift events",
            description = "Returns VM state drift events (unexpected state changes detected during sync)"
    )
    public ResponseEntity<Page<VmStateHistoryDTO>> getDriftEvents(
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {

        Page<VmStateHistory> driftEvents = stateSyncService.getDriftEvents(page, size);
        Page<VmStateHistoryDTO> dtos = driftEvents.map(VmStateHistoryDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/drift-events/count")
    @Operation(
            summary = "Count drift events",
            description = "Returns the count of drift events in a date range"
    )
    public ResponseEntity<Map<String, Object>> countDriftEvents(
            @Parameter(description = "Start date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        long count = stateSyncService.countDriftEventsInRange(startDate, endDate);

        return ResponseEntity.ok(Map.of(
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "driftEventCount", count
        ));
    }

    @GetMapping("/drift-events/report")
    @Operation(
            summary = "Get drift events report",
            description = "Returns drift events in a date range for reporting"
    )
    public ResponseEntity<List<VmStateHistoryDTO>> getDriftEventsReport(
            @Parameter(description = "Start date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<VmStateHistory> driftEvents = stateSyncService.getDriftEventsInRange(startDate, endDate);
        List<VmStateHistoryDTO> dtos = driftEvents.stream()
                .map(VmStateHistoryDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }
}

