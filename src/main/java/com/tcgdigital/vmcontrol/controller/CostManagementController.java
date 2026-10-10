package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ApplyRightsizingRequestDTO;
import com.tcgdigital.vmcontrol.dto.CostForecastDTO;
import com.tcgdigital.vmcontrol.dto.CostReconciliationRowDTO;
import com.tcgdigital.vmcontrol.dto.CostSummaryDTO;
import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.ReservationCoverageSnapshotDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.dto.SpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.TeamSpendTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.VmCostDetailDTO;
import com.tcgdigital.vmcontrol.repository.ReservationCoverageSnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import com.tcgdigital.vmcontrol.service.CostExplorerBillingService;
import com.tcgdigital.vmcontrol.service.CostForecastService;
import com.tcgdigital.vmcontrol.service.CostReconciliationService;
import com.tcgdigital.vmcontrol.service.CostSnapshotService;
import com.tcgdigital.vmcontrol.service.ExcelExportService;
import com.tcgdigital.vmcontrol.service.TagReconciliationService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.VmResizeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin-only cost reporting: KPI summary, spend breakdowns, idle waste, rightsizing candidates,
 * per-VM cost detail, and the spend trend. All figures are estimates (see {@link CostEstimationService}).
 */
@RestController
@org.springframework.validation.annotation.Validated
@RequestMapping("/api/v1/cost-management")
@Tag(name = "Cost Management", description = "Estimated cost reporting for admins")
public class CostManagementController {

    private final CostEstimationService costEstimationService;
    private final CostSnapshotService costSnapshotService;
    private final TagReconciliationService tagReconciliationService;
    private final VmRepository vmRepository;
    private final CostExplorerBillingService costExplorerBillingService;
    private final CostReconciliationService costReconciliationService;
    private final ReservationCoverageSnapshotRepository reservationCoverageSnapshotRepository;
    private final CostForecastService costForecastService;
    private final ExcelExportService excelExportService;
    private final VmResizeService vmResizeService;
    private final UserService userService;

    public CostManagementController(CostEstimationService costEstimationService,
                                     CostSnapshotService costSnapshotService,
                                     TagReconciliationService tagReconciliationService,
                                     VmRepository vmRepository,
                                     CostExplorerBillingService costExplorerBillingService,
                                     CostReconciliationService costReconciliationService,
                                     ReservationCoverageSnapshotRepository reservationCoverageSnapshotRepository,
                                     CostForecastService costForecastService,
                                     ExcelExportService excelExportService,
                                     VmResizeService vmResizeService,
                                     UserService userService) {
        this.costEstimationService = costEstimationService;
        this.costSnapshotService = costSnapshotService;
        this.tagReconciliationService = tagReconciliationService;
        this.vmRepository = vmRepository;
        this.costExplorerBillingService = costExplorerBillingService;
        this.costReconciliationService = costReconciliationService;
        this.reservationCoverageSnapshotRepository = reservationCoverageSnapshotRepository;
        this.costForecastService = costForecastService;
        this.excelExportService = excelExportService;
        this.vmResizeService = vmResizeService;
        this.userService = userService;
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

    @GetMapping("/spend-trend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SpendTrendPointDTO>> getSpendTrend(@RequestParam(defaultValue = "90") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(400) int days) {
        return ResponseEntity.ok(costEstimationService.getSpendTrend(days));
    }

    @GetMapping("/spend-trend-by-team")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TeamSpendTrendPointDTO>> getSpendTrendByTeam(@RequestParam(defaultValue = "90") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(400) int days) {
        return ResponseEntity.ok(costEstimationService.getSpendTrendByTeam(days));
    }

    @GetMapping("/idle-waste")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<IdleWasteRowDTO>> getIdleWaste(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getIdleWaste(com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 100)));
    }

    @GetMapping("/rightsizing")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<RightsizingCandidateDTO>> getRightsizingCandidates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getRightsizingCandidates(com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 100)));
    }

    /**
     * Apply the current rightsizing recommendation. 400 when the type is malformed or is not the
     * type currently recommended for the VM (E08-T05, M11); the lock, running-operation and
     * stopped-VM checks still apply.
     */
    @PostMapping("/rightsizing/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> applyRightsizing(@Valid @RequestBody ApplyRightsizingRequestDTO request) {
        vmResizeService.applyInstanceTypeChange(request.vmId(), request.targetInstanceType(), userService.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/vm-detail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<VmCostDetailDTO>> getVmCostDetail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(costEstimationService.getVmCostDetail(com.tcgdigital.vmcontrol.controller.support.Paging.of(page, size, 100)));
    }

    @PostMapping("/snapshots/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> backfillSnapshots(@RequestParam(defaultValue = "30") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(90) int days) {
        costSnapshotService.backfillHistoricalSnapshots(days);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tags/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TagReconciliationService.Result> reconcileTags() {
        return ResponseEntity.ok(tagReconciliationService.reconcileAll());
    }

    @GetMapping("/tags/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Long>> getTagStatus() {
        return ResponseEntity.ok(Map.of(
                "tagged", vmRepository.countByIsActiveTrueAndTagsSyncedAtIsNotNull(),
                "untagged", vmRepository.countByIsActiveTrueAndTagsSyncedAtIsNull()
        ));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<CostReconciliationRowDTO>> getReconciliation(@RequestParam(defaultValue = "30") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(400) int days) {
        return ResponseEntity.ok(costReconciliationService.getReconciliation(days));
    }

    @GetMapping("/reconciliation/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportReconciliation(@RequestParam(defaultValue = "90") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(400) int days) {
        List<CostReconciliationRowDTO> rows = costReconciliationService.getReconciliation(days);
        byte[] xlsx = excelExportService.toWorkbook("Estimated vs Actual",
                List.of("Environment", "Estimated Cost (window)", "Estimated Cost (days with actuals)", "Actual Cost",
                        "Variance %", "Actual coverage (days)"),
                rows.stream()
                        .map(r -> new Object[]{r.environmentName(), r.estimatedCost(), r.estimatedCostOnActualDays(),
                                r.actualCost(), r.variancePercent(), r.daysWithActuals() + "/" + r.daysInWindow()})
                        .toList());
        return excelResponse(xlsx, "estimated-vs-actual-cost");
    }

    @GetMapping("/idle-waste/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportIdleWaste() {
        List<IdleWasteRowDTO> rows = costEstimationService.getIdleWaste(PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        byte[] xlsx = excelExportService.toWorkbook("Idle & Waste",
                List.of("VM", "Environment", "Group", "Monthly Cost", "Latest CPU %", "Idle Since", "Monthly Idle Cost"),
                rows.stream()
                        .map(r -> new Object[]{
                                nameOrId(r.vmName(), r.vmId()), r.environmentName(), r.groupName(),
                                r.monthlyCost(), r.latestCpuUtilization(), formatTimestamp(r.idleSince()), r.monthlyIdleCost()
                        })
                        .toList());
        return excelResponse(xlsx, "idle-and-waste");
    }

    @GetMapping("/rightsizing/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportRightsizing() {
        List<RightsizingCandidateDTO> rows = costEstimationService.getRightsizingCandidates(PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        byte[] xlsx = excelExportService.toWorkbook("Rightsizing Recommendations",
                List.of("VM", "Environment", "Direction", "Current Instance", "Suggested Instance", "Source", "Avg CPU %", "Peak CPU %", "Current Cost", "Est. Savings"),
                rows.stream()
                        .map(r -> new Object[]{
                                nameOrId(r.vmName(), r.vmId()), r.environmentName(), r.direction(), r.currentInstanceType(), r.suggestedInstanceType(),
                                r.source(), r.avgCpuUtilization(), r.peakCpuUtilization(), r.currentMonthlyCost(), r.estimatedMonthlySavings()
                        })
                        .toList());
        return excelResponse(xlsx, "rightsizing-recommendations");
    }

    @GetMapping("/vm-detail/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportVmCostDetail() {
        List<VmCostDetailDTO> rows = costEstimationService.getVmCostDetail(PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        byte[] xlsx = excelExportService.toWorkbook("VM Cost Detail",
                List.of("VM", "Environment", "Group", "Status", "Instance Type", "Region", "Runtime (h)", "Storage (GiB)",
                        "Monthly Cost", "Price basis"),
                rows.stream()
                        .map(r -> new Object[]{
                                nameOrId(r.vmName(), r.vmId()), r.environmentName(), r.groupName(), r.status(),
                                r.instanceType(), r.region(), r.runtimeHours(), r.storageGib(), r.monthlyCost(),
                                !Boolean.TRUE.equals(r.costKnown()) ? "Unknown"
                                        : Boolean.TRUE.equals(r.priceApproximate()) ? "Other-region rate (approx.)" : "Exact region"
                        })
                        .toList());
        return excelResponse(xlsx, "vm-cost-detail");
    }

    private String nameOrId(String name, String id) {
        return name != null ? name : id;
    }

    private String formatTimestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private ResponseEntity<byte[]> excelResponse(byte[] xlsx, String baseFileName) {
        String filename = baseFileName + "-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }

    @PostMapping("/actuals/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CostExplorerBillingService.IngestResult> backfillActuals(@RequestParam(defaultValue = "30") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(90) int days) {
        return ResponseEntity.ok(costExplorerBillingService.backfillActualCosts(days));
    }

    @GetMapping("/reservations/coverage")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ReservationCoverageSnapshotDTO>> getReservationCoverageTrend(
            @RequestParam(defaultValue = "30") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(400) int days) {
        Date since = Date.valueOf(LocalDate.now().minusDays(days));
        return ResponseEntity.ok(
                reservationCoverageSnapshotRepository.findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(since)
                        .stream()
                        .map(ReservationCoverageSnapshotDTO::fromEntity)
                        .toList());
    }

    @GetMapping("/reservations/utilization")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReservationCoverageSnapshotDTO> getLatestReservationUtilization() {
        return reservationCoverageSnapshotRepository.findTopByOrderBySnapshotDateDesc()
                .map(ReservationCoverageSnapshotDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/forecast")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CostForecastDTO> getForecast(
            @RequestParam(defaultValue = "30") @jakarta.validation.constraints.Min(14) @jakarta.validation.constraints.Max(400) int historyDays,
            @RequestParam(defaultValue = "14") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(90) int forecastDays) {
        return ResponseEntity.ok(costForecastService.getForecast(historyDays, forecastDays));
    }
}
