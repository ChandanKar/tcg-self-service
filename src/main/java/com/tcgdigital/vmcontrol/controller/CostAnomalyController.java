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

    public CostAnomalyController(CostAnomalyMonitorService monitorService) {
        this.monitorService = monitorService;
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
