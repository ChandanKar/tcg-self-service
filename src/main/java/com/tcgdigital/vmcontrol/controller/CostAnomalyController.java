package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.CostAnomalyMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AWS cost anomalies (E21): the monitors and the environment each one watches. */
@RestController
@RequestMapping("/api/v1/cost-anomalies")
@Tag(name = "Cost Anomalies", description = "AWS Cost Anomaly Detection monitors and anomalies")
public class CostAnomalyController {

    private final CostAnomalyMonitorService monitorService;
    private final com.tcgdigital.vmcontrol.service.CostAnomalyService anomalyService;
    private final com.tcgdigital.vmcontrol.service.UserService userService;

    public CostAnomalyController(CostAnomalyMonitorService monitorService,
                                 com.tcgdigital.vmcontrol.service.CostAnomalyService anomalyService,
                                 com.tcgdigital.vmcontrol.service.UserService userService) {
        this.monitorService = monitorService;
        this.anomalyService = anomalyService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cost anomalies across environments, newest first")
    public org.springframework.data.domain.Page<com.tcgdigital.vmcontrol.dto.CostAnomalyDTO> list(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String environmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (days < 1 || days > 365) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException("days must be between 1 and 365");
        }
        return anomalyService.list(days, environmentId, com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 100,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "anomalyStart")));
    }

    public record FeedbackRequest(@NotBlank(message = "Feedback is required") String value) {}

    public record FeedbackResult(String anomalyId, String feedback) {}

    @PostMapping("/{anomalyId}/feedback")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Say whether an anomaly was real: YES, NO or PLANNED_ACTIVITY (the environment's owners)")
    public FeedbackResult feedback(@PathVariable String anomalyId, @Valid @RequestBody FeedbackRequest request) {
        com.tcgdigital.vmcontrol.model.CostAnomaly anomaly =
                anomalyService.feedback(anomalyId, userService.getCurrentUserId(), request.value());
        return new FeedbackResult(anomaly.getAnomalyId(), anomaly.getFeedback());
    }

    /** Body of a manual link; environmentId null or blank unlinks. ARNs contain '/', so not in the path. */
    public record LinkRequest(@NotBlank(message = "Monitor ARN is required") String monitorArn, String environmentId) {}

    @GetMapping("/monitors")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Anomaly monitors and their environments")
    public List<CostAnomalyMonitorService.MonitorView> monitors() {
        return monitorService.list();
    }

    @PostMapping("/monitors/sync")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Refresh monitors from AWS (and create missing ones when cost.anomalies.manage-monitors is on)")
    public CostAnomalyMonitorService.SyncResult sync() {
        return monitorService.sync();
    }

    @PutMapping("/monitors/link")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Link a monitor to an environment (or unlink it)")
    public CostAnomalyMonitorService.MonitorView link(@Valid @RequestBody LinkRequest request) {
        return monitorService.link(request.monitorArn(), request.environmentId());
    }
}
