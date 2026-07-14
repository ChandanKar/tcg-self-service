package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CostSummaryDTO;
import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.dto.SpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.TeamSpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.VmCostDetailDTO;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import com.tcgdigital.vmcontrol.service.CostSnapshotService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only cost reporting: KPI summary, spend breakdowns, idle waste, rightsizing candidates,
 * per-VM cost detail, and the spend trend. All figures are estimates (see {@link CostEstimationService}).
 */
@RestController
@RequestMapping("/api/v1/cost-management")
@Tag(name = "Cost Management", description = "Estimated cost reporting for admins")
public class CostManagementController {

    private final CostEstimationService costEstimationService;
    private final CostSnapshotService costSnapshotService;

    public CostManagementController(CostEstimationService costEstimationService,
                                     CostSnapshotService costSnapshotService) {
        this.costEstimationService = costEstimationService;
        this.costSnapshotService = costSnapshotService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CostSummaryDTO> getSummary() {
        return ResponseEntity.ok(costEstimationService.getSummary());
    }

    @GetMapping("/spend-by-environment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SpendByDimensionDTO>> getSpendByEnvironment() {
        return ResponseEntity.ok(costEstimationService.getSpendByEnvironment());
    }

    @GetMapping("/spend-by-vm-type")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SpendByDimensionDTO>> getSpendByVmType() {
        return ResponseEntity.ok(costEstimationService.getSpendByVmType());
    }

    @GetMapping("/spend-by-team")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SpendByDimensionDTO>> getSpendByTeam() {
        return ResponseEntity.ok(costEstimationService.getSpendByTeam());
    }

    @GetMapping("/spend-trend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SpendTrendPointDTO>> getSpendTrend(@RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(costEstimationService.getSpendTrend(days));
    }

    @GetMapping("/spend-trend-by-team")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TeamSpendTrendPointDTO>> getSpendTrendByTeam(@RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(costEstimationService.getSpendTrendByTeam(days));
    }

    @GetMapping("/idle-waste")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<IdleWasteRowDTO>> getIdleWaste(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getIdleWaste(PageRequest.of(page, size)));
    }

    @GetMapping("/rightsizing")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<RightsizingCandidateDTO>> getRightsizingCandidates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getRightsizingCandidates(PageRequest.of(page, size)));
    }

    @GetMapping("/vm-detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<VmCostDetailDTO>> getVmCostDetail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getVmCostDetail(PageRequest.of(page, size)));
    }

    @PostMapping("/snapshots/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> backfillSnapshots(@RequestParam(defaultValue = "30") int days) {
        costSnapshotService.backfillHistoricalSnapshots(days);
        return ResponseEntity.ok().build();
    }
}
