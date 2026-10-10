package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AuditLogDTO;
import com.tcgdigital.vmcontrol.dto.AuditReportDTO;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.service.AuditFilter;
import com.tcgdigital.vmcontrol.service.AuditService;
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
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final UserService userService;
    private final SecurityService securityService;

    public AuditController(AuditService auditService, UserService userService, SecurityService securityService) {
        this.securityService = securityService;
        this.auditService = auditService;
        this.userService = userService;
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Get audit logs",
            description = "Audit logs matching every given filter together (E11-T04). from/to are instants "
                    + "(to exclusive); startDate/endDate are deprecated day fallbacks. Default: the last 7 days. "
                    + "An ENV_ADMIN scoped to assigned environments sees only those."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Audit logs retrieved successfully"
            )
    })
    public ResponseEntity<Page<AuditLogDTO>> getAuditLogs(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 500)") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "Filter by environment ID") @RequestParam(required = false) String environmentId,
            @Parameter(description = "Filter by user ID") @RequestParam(required = false) String userId,
            @Parameter(description = "Filter by action type") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "true = succeeded only, false = failed only") @RequestParam(required = false) Boolean success,
            @Parameter(description = "From (ISO instant, inclusive)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
            @Parameter(description = "To (ISO instant, exclusive)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant to,
            @Parameter(description = "Deprecated: start date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Deprecated: end date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "Search text") @RequestParam(required = false) String search) {

        AuditFilter filter = filter(environmentId, userId, action, success, from, to, startDate, endDate, search);
        Page<AuditLog> logs = auditService.search(filter, com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 500));
        return ResponseEntity.ok(logs.map(AuditLogDTO::fromEntity));
    }

    @GetMapping("/logs/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Audit totals for the same filters as /logs (E11-T04)")
    public ResponseEntity<com.tcgdigital.vmcontrol.dto.AuditStatsDTO> getAuditStats(
            @RequestParam(required = false) String environmentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(auditService.stats(
                filter(environmentId, userId, action, success, from, to, startDate, endDate, search)));
    }

    /** Build the combined filter, scoped to what the caller may see; an environment outside it is a 404. */
    private AuditFilter filter(String environmentId, String userId, AuditAction action, Boolean success,
                               java.time.Instant from, java.time.Instant to, LocalDate startDate, LocalDate endDate,
                               String search) {
        java.util.List<String> allowed = securityService.administeredEnvironmentIdsOrAll();
        if (environmentId != null && allowed != null && !allowed.contains(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.ResourceNotFoundException("Environment", environmentId);
        }
        java.sql.Timestamp fromTs = from != null ? java.sql.Timestamp.from(from)
                : startDate != null ? java.sql.Timestamp.valueOf(startDate.atStartOfDay()) : null;
        java.sql.Timestamp toTs = to != null ? java.sql.Timestamp.from(to)
                : endDate != null ? java.sql.Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()) : null;
        if (fromTs == null && toTs == null) {
            fromTs = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
        }
        return new AuditFilter(allowed, blankToNull(environmentId), blankToNull(userId),
                action != null ? action.name() : null, success, fromTs, toTs, blankToNull(search));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @GetMapping("/logs/my")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get my activity logs",
            description = "Returns audit logs for the currently authenticated user, with optional date-range filter"
    )
    public ResponseEntity<Page<AuditLogDTO>> getMyLogs(
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "Filter by environment ID") @RequestParam(required = false) String environmentId,
            @Parameter(description = "Filter by action type") @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Start date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (YYYY-MM-DD)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String userId = userService.getCurrentUserId();
        Page<AuditLog> logs = auditService.getUserActivityLogs(
                userId, startDate, endDate, environmentId, action, page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/recent")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(
            summary = "Get audit logs for an environment",
            description = "Retrieves audit logs for a specific environment"
    )
    public ResponseEntity<Page<AuditLogDTO>> getLogsForEnvironment(
            @Parameter(description = "Environment ID") @PathVariable String environmentId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {

        securityService.assertCanAdminister(environmentId);
        Page<AuditLog> logs = auditService.getLogsForEnvironment(environmentId, page, size);
        Page<AuditLogDTO> dtos = logs.map(AuditLogDTO::fromEntity);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/logs/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
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
    @PreAuthorize("isAuthenticated()")
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

