package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AuditLogDTO;
import com.tcgdigital.vmcontrol.dto.AuditReportDTO;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import java.util.stream.Collectors;

/**
 * REST controller for audit logs and reporting.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Audit logs and compliance reporting")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/logs")
    @Operation(
            summary = "Get audit logs",
            description = "Retrieves audit logs with optional filtering"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Audit logs retrieved successfully"
            )
    })
    public ResponseEntity<Page<AuditLogDTO>> getAuditLogs(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "Filter by environment ID") @RequestParam(required = false) String environmentId,
            @Parameter(description = "Filter by user ID") @RequestParam(required = false) String userId,
            @Parameter(description = "Filter by action type") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Start date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Search text") @RequestParam(required = false) String search) {

        Page<AuditLog> logs;

        if (environmentId != null) {
            logs = auditService.getLogsForEnvironment(environmentId, page, size);
        } else if (userId != null) {
            logs = auditService.getLogsForUser(userId, page, size);
        } else if (action != null) {
            logs = auditService.getLogsByAction(action, page, size);
        } else if (startDate != null && endDate != null) {
            logs = auditService.getLogsInDateRange(startDate, endDate, page, size);
        } else if (search != null && !search.isBlank()) {
            logs = auditService.searchLogs(search, page, size);
        } else {
            // Default: return recent logs
            logs = auditService.getLogsInDateRange(
                    LocalDate.now().minusDays(7),
                    LocalDate.now(),
                    page,
                    size
            );
        }

        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/recent")
    @Operation(
            summary = "Get recent audit logs",
            description = "Retrieves the most recent 100 audit logs"
    )
    public ResponseEntity<List<AuditLogDTO>> getRecentLogs() {
        List<AuditLog> logs = auditService.getRecentLogs();
        List<AuditLogDTO> dtos = logs.stream()
                .map(AuditLogDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/environment/{environmentId}")
    @Operation(
            summary = "Get audit logs for an environment",
            description = "Retrieves audit logs for a specific environment"
    )
    public ResponseEntity<Page<AuditLogDTO>> getLogsForEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        Page<AuditLog> logs = auditService.getLogsForEnvironment(environmentId, page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/user/{userId}")
    @Operation(
            summary = "Get audit logs for a user",
            description = "Retrieves audit logs for a specific user"
    )
    public ResponseEntity<Page<AuditLogDTO>> getLogsForUser(
            @Parameter(description = "User ID") @PathVariable String userId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        Page<AuditLog> logs = auditService.getLogsForUser(userId, page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/target/{targetType}/{targetId}")
    @Operation(
            summary = "Get audit logs for a target",
            description = "Retrieves audit logs for a specific target (vm, group, environment, etc.)"
    )
    public ResponseEntity<Page<AuditLogDTO>> getLogsForTarget(
            @Parameter(description = "Target type (vm, group, environment, lock, operation)")
                @PathVariable String targetType,
            @Parameter(description = "Target ID") @PathVariable String targetId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        Page<AuditLog> logs = auditService.getLogsForTarget(targetType, targetId, page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/failures")
    @Operation(
            summary = "Get failed operations",
            description = "Retrieves audit logs for failed operations"
    )
    public ResponseEntity<Page<AuditLogDTO>> getFailedOperations(
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        Page<AuditLog> logs = auditService.getFailedOperations(page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/report")
    @Operation(
            summary = "Generate audit report",
            description = "Generates an audit report for a date range"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Report generated successfully",
                    content = @Content(schema = @Schema(implementation = AuditReportDTO.class))
            )
    })
    public ResponseEntity<AuditReportDTO> generateReport(
            @Parameter(description = "Start date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        AuditReportDTO report = new AuditReportDTO();
        report.setStartDate(startDate);
        report.setEndDate(endDate);

        // Get action counts by type
        Map<AuditAction, Long> actionCounts = auditService.getActionCountsByType(startDate, endDate);
        report.setActionCounts(actionCounts);

        // Calculate totals
        long totalActions = actionCounts.values().stream().mapToLong(Long::longValue).sum();
        report.setTotalActions(totalActions);

        // Get user activity counts
        Map<String, Long> userCounts = auditService.getActionCountsByUser(startDate, endDate);
        report.setUserActivityCounts(userCounts);

        // Get environment activity
        List<AuditService.EnvironmentActivitySummary> envActivities =
                auditService.getActionCountsByEnvironment(startDate, endDate);
        report.setEnvironmentActivities(
                envActivities.stream()
                        .map(ea -> new AuditReportDTO.EnvironmentActivityDTO(
                                ea.getEnvironmentId(),
                                ea.getEnvironmentName(),
                                ea.getActionCount()
                        ))
                        .toList()
        );

        // Get recent logs for the period (first page)
        Page<AuditLog> recentLogs = auditService.getLogsInDateRange(startDate, endDate, 0, 20);
        report.setRecentLogs(
                recentLogs.getContent().stream()
                        .map(AuditLogDTO::fromEntity)
                        .toList()
        );

        return ResponseEntity.ok(report);
    }

    @GetMapping("/report/locks")
    @Operation(
            summary = "Get lock operations report",
            description = "Retrieves all lock-related operations for compliance"
    )
    public ResponseEntity<List<AuditLogDTO>> getLockOperationsReport(
            @Parameter(description = "Start date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<AuditLog> logs = auditService.getLockOperationsReport(startDate, endDate);
        List<AuditLogDTO> dtos = logs.stream()
                .map(AuditLogDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/report/vm-operations")
    @Operation(
            summary = "Get VM operations report",
            description = "Retrieves all VM start/stop operations for compliance"
    )
    public ResponseEntity<List<AuditLogDTO>> getVmOperationsReport(
            @Parameter(description = "Start date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<AuditLog> logs = auditService.getVmOperationsReport(startDate, endDate);
        List<AuditLogDTO> dtos = logs.stream()
                .map(AuditLogDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/actions")
    @Operation(
            summary = "Get available audit actions",
            description = "Returns list of all possible audit action types"
    )
    public ResponseEntity<List<String>> getAvailableActions() {
        List<String> actions = java.util.Arrays.stream(AuditAction.values())
                .map(Enum::name)
                .toList();
        return ResponseEntity.ok(actions);
    }
}

