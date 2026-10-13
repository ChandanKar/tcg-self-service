package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CarbonFleetDTO;
import com.tcgdigital.vmcontrol.service.ExcelExportService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.carbon.CarbonIngestionService;
import com.tcgdigital.vmcontrol.service.carbon.CarbonReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Carbon footprint (E37): ingestion of the AWS carbon Data Export, an environment's attributed
 * emissions for those who can view it, and the fleet view and Excel export for admins (E37-T03).
 * 404 while carbon.enabled is off.
 */
@RestController
@RequestMapping("/api/v1")
public class CarbonController {

    private final CarbonIngestionService ingestionService;
    private final CarbonReportService reportService;
    private final SecurityService securityService;
    private final ExcelExportService excelExportService;

    public CarbonController(CarbonIngestionService ingestionService, CarbonReportService reportService,
                            SecurityService securityService, ExcelExportService excelExportService) {
        this.ingestionService = ingestionService;
        this.reportService = reportService;
        this.securityService = securityService;
        this.excelExportService = excelExportService;
    }

    private static ResponseEntity<Object> off() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found", "message", "Carbon data is not enabled"));
    }

    /** Reads new or changed export files now instead of waiting for the daily run (E37-T01). */
    @PostMapping("/cost-management/carbon/ingest")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> ingest() {
        if (!ingestionService.isEnabled()) return off();
        return ResponseEntity.ok(ingestionService.ingest());
    }

    /** An environment's last 12 months of attributed emissions next to its cost (E37-T03). */
    @GetMapping("/environments/{environmentId}/carbon")
    public ResponseEntity<Object> environment(@PathVariable String environmentId) {
        if (!ingestionService.isEnabled()) return off();
        securityService.assertCanView(environmentId);
        return ResponseEntity.ok(reportService.environment(environmentId));
    }

    /** Carbon by environment for a month (the newest with data when none is given), with the trend (E37-T03). */
    @GetMapping("/cost-management/carbon")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> fleet(@RequestParam(required = false) String month) {
        if (!ingestionService.isEnabled()) return off();
        YearMonth m = month(month);
        return ResponseEntity.ok(reportService.fleet(m));
    }

    @GetMapping("/cost-management/carbon/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> export(@RequestParam(required = false) String month) {
        if (!ingestionService.isEnabled()) return off();
        CarbonFleetDTO fleet = reportService.fleet(month(month));
        byte[] xlsx = excelExportService.toWorkbook("Carbon",
                java.util.List.of("Month", "Environment", "kg CO2e (market-based)", "kg CO2e (location-based)", "Cost (USD)",
                        "kg CO2e per USD"),
                fleet.rows().stream().map(r -> new Object[]{fleet.month(), r.environmentName(), r.mbmKg(), r.lbmKg(), r.cost(),
                        r.kgPerDollar()}).toList());
        String name = "carbon-" + (fleet.month() == null ? "none" : fleet.month()) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }

    private static YearMonth month(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return YearMonth.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException("month must be yyyy-MM");
        }
    }
}
