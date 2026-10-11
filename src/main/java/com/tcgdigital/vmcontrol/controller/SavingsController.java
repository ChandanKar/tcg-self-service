package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.EfficiencyTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.SavingsBreakdownRowDTO;
import com.tcgdigital.vmcontrol.dto.SavingsSummaryDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.service.SavingsLedgerService;
import com.tcgdigital.vmcontrol.service.SavingsReportService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Savings and Cost Efficiency (E17-T06): fleet reports for admins, and one environment's summary
 * for anyone who can see it.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Savings", description = "Realised savings ledger, potential savings and Cost Efficiency %")
public class SavingsController {

    private final SavingsReportService reportService;
    private final SavingsLedgerService ledgerService;
    private final SecurityService securityService;

    public SavingsController(SavingsReportService reportService, SavingsLedgerService ledgerService,
                             SecurityService securityService) {
        this.reportService = reportService;
        this.ledgerService = ledgerService;
        this.securityService = securityService;
    }

    @GetMapping("/savings/summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fleet savings for a month", description = "month=yyyy-MM (default: this month)")
    public SavingsSummaryDTO summary(@RequestParam(required = false) String month) {
        return reportService.summary(month(month), null);
    }

    @GetMapping("/savings/by-cause")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SavingsBreakdownRowDTO> byCause(@RequestParam(required = false) String month) {
        return reportService.byCause(month(month), null);
    }

    @GetMapping("/savings/by-environment")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SavingsBreakdownRowDTO> byEnvironment(@RequestParam(required = false) String month) {
        return reportService.byEnvironment(month(month));
    }

    @GetMapping("/savings/by-team")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SavingsBreakdownRowDTO> byTeam(@RequestParam(required = false) String month) {
        return reportService.byTeam(month(month));
    }

    @GetMapping("/savings/efficiency/trend")
    @PreAuthorize("hasRole('ADMIN')")
    public List<EfficiencyTrendPointDTO> efficiencyTrend(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(required = false) String environmentId) {
        return reportService.efficiencyTrend(days, environmentId);
    }

    @GetMapping("/savings/export")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The month's savings ledger as Excel", description = "Totals by cause follow the rows")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month) {
        YearMonth m = month(month);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"savings-ledger-" + m + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(reportService.exportMonth(m));
    }

    @PostMapping("/savings/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rebuild the ledger", description = "The last N complete days, 1..90")
    public Map<String, Integer> backfill(@RequestParam @Min(1) @Max(90) int days) {
        return Map.of("days", ledgerService.backfill(days));
    }

    @GetMapping("/environments/{environmentId}/savings")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One environment's savings for a month", description = "For anyone who can see the environment")
    public SavingsSummaryDTO environmentSavings(@PathVariable String environmentId,
                                                @RequestParam(required = false) String month) {
        securityService.assertCanView(environmentId);
        return reportService.summary(month(month), environmentId);
    }

    private static YearMonth month(String month) {
        if (month == null || month.isBlank()) return YearMonth.now(java.time.ZoneOffset.UTC);
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ValidationException("month must look like 2026-09");
        }
    }
}
