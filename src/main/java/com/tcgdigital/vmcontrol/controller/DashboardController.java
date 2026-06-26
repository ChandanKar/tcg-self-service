package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
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

    public DashboardController(DashboardSummaryService dashboardSummaryService) {
        this.dashboardSummaryService = dashboardSummaryService;
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
}
