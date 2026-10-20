package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AttentionSummaryDTO;
import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
import com.tcgdigital.vmcontrol.service.AttentionSummaryService;
import com.tcgdigital.vmcontrol.service.DashboardSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Role-aware dashboard summaries")
public class DashboardController {

    private final DashboardSummaryService dashboardSummaryService;
    private final AttentionSummaryService attentionSummaryService;

    public DashboardController(DashboardSummaryService dashboardSummaryService,
                               AttentionSummaryService attentionSummaryService) {
        this.dashboardSummaryService = dashboardSummaryService;
        this.attentionSummaryService = attentionSummaryService;
    }

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get dashboard summary",
            description = "Returns normal-user or admin dashboard data based on the current user's role."
    )
    public ResponseEntity<DashboardSummaryDTO> getSummary() {
        return ResponseEntity.ok(dashboardSummaryService.getSummary());
    }

    @GetMapping("/attention")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "What needs an administrator's attention",
            description = "Pending access requests and approvals with the oldest wait, failed or stuck operations in "
                    + "the last 24 hours with environment names, VMs to review, budgets near their limit, late "
                    + "scheduled jobs, the health line (jobs on time, last state sync, drift in 24 hours) and the "
                    + "month's budget plan (null without enabled budgets). Administrators only."
    )
    public ResponseEntity<AttentionSummaryDTO> getAttention() {
        return ResponseEntity.ok(attentionSummaryService.summary());
    }
}
