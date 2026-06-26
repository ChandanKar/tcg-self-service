package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.dto.VmStateHistoryDTO;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.service.EksSyncService;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.service.VmInventoryService;
import com.tcgdigital.vmcontrol.service.VmMetricsArchiveService;
import com.tcgdigital.vmcontrol.service.VmMetricsService;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final EksSyncService eksSyncService;
    private final VmInventoryService inventoryService;
    private final VmMetricsService metricsService;
    private final VmMetricsArchiveService archiveService;

    public MonitoringController(StateSyncService stateSyncService,
                                EksSyncService eksSyncService,
                                VmInventoryService inventoryService,
                                VmMetricsService metricsService,
                                VmMetricsArchiveService archiveService) {
        this.stateSyncService = stateSyncService;
        this.eksSyncService = eksSyncService;
        this.inventoryService = inventoryService;
        this.metricsService = metricsService;
        this.archiveService = archiveService;
    }

    @GetMapping("/sync-status")
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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

    @PostMapping("/sync/eks")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Trigger manual EKS sync",
            description = "Triggers an immediate EKS cluster and node group synchronisation"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "EKS sync completed"),
            @ApiResponse(responseCode = "503", description = "EKS cloud provider not available")
    })
    public ResponseEntity<Map<String, Object>> triggerEksSync() {
        int synced = eksSyncService.syncAllEksClusters();
        if (synced < 0) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "unavailable",
                    "message", "EKS cloud provider not available — check AWS credentials"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "nodeGroupsSynced", synced
        ));
    }

    @PostMapping("/inventory/sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Trigger manual VM inventory sync",
            description = "Fetches cloud inventory snapshots for active VMs"
    )
    public ResponseEntity<Map<String, Object>> triggerInventorySync() {
        int synced = inventoryService.syncAllInventory();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "vmsSynced", synced
        ));
    }

    @PostMapping("/metrics/sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Trigger manual VM metrics sync",
            description = "Fetches latest utilization metrics for running VMs"
    )
    public ResponseEntity<Map<String, Object>> triggerMetricsSync() {
        int samples = metricsService.syncRunningVmMetrics();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "samplesSaved", samples
        ));
    }

    @PostMapping("/metrics/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Trigger manual VM metrics archive",
            description = "Moves old raw metric samples from hot storage to archive storage"
    )
    public ResponseEntity<Map<String, Object>> triggerMetricsArchive() {
        int archived = archiveService.archiveOldRawSamples();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "samplesArchived", archived
        ));
    }

    @GetMapping("/vms/{vmId}/history")
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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

