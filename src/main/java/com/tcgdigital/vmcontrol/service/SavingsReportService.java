package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EfficiencyTrendPointDTO;
import com.tcgdigital.vmcontrol.dto.SavingsBreakdownRowDTO;
import com.tcgdigital.vmcontrol.dto.SavingsSummaryDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.EfficiencySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EfficiencySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.SavingsLedgerEntryRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Monthly savings reporting, breakdowns, trend and the ledger export (E17-T06). */
@Service
public class SavingsReportService {

    static final BigDecimal TARGET_VARIANCE = BigDecimal.valueOf(15);
    static final List<String> EXPORT_HEADERS = List.of("Date", "Environment", "Team", "VM", "Cause", "Avoided hours",
            "Rate", "Gross", "Residual EBS", "Residual IPv4", "Commitment coverage %", "Commitment adj.", "Net",
            "Approximate price", "Note");

    private final SavingsLedgerEntryRepository ledgerRepository;
    private final EfficiencySnapshotRepository efficiencyRepository;
    private final CostDailySnapshotRepository costRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final PotentialSavingsService potentialSavingsService;
    private final ExcelExportService excelExportService;
    private final CostDayBoundary dayBoundary;

    public SavingsReportService(SavingsLedgerEntryRepository ledgerRepository,
                                EfficiencySnapshotRepository efficiencyRepository,
                                CostDailySnapshotRepository costRepository, EnvironmentRepository environmentRepository,
                                VmRepository vmRepository, PotentialSavingsService potentialSavingsService,
                                ExcelExportService excelExportService, CostDayBoundary dayBoundary) {
        this.ledgerRepository = ledgerRepository;
        this.efficiencyRepository = efficiencyRepository;
        this.costRepository = costRepository;
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.potentialSavingsService = potentialSavingsService;
        this.excelExportService = excelExportService;
        this.dayBoundary = dayBoundary;
    }

    @Transactional(readOnly = true)
    public SavingsSummaryDTO summary(YearMonth month, String environmentId) {
        List<SavingsLedgerEntry> entries = entries(month, environmentId);
        BigDecimal net = sum(entries, SavingsLedgerEntry::getNetSaving);
        BigDecimal gross = sum(entries, SavingsLedgerEntry::getGrossSaving);
        BigDecimal storage = sum(entries, SavingsLedgerEntry::getResidualStorageCost);
        BigDecimal ipv4 = sum(entries, SavingsLedgerEntry::getResidualIpv4Cost);
        BigDecimal adjustment = sum(entries, SavingsLedgerEntry::getCommitmentAdjustment);

        BigDecimal potential;
        BigDecimal optimisable;
        BigDecimal efficiency;
        BigDecimal offHoursShare = null;
        if (!month.isBefore(YearMonth.from(dayBoundary.today()))) {
            PotentialSavingsService.Result current = potentialSavingsService.compute(environmentId);
            potential = current.potentialSavings();
            optimisable = current.optimisableSpend();
            efficiency = current.efficiencyPercent();
            offHoursShare = current.offHoursShare();
        } else {
            EfficiencySnapshot last = lastSnapshot(month, environmentId);
            potential = last == null ? null : last.getPotentialSavings();
            optimisable = last == null ? null : last.getOptimisableSpend();
            efficiency = last == null ? null : last.getEfficiencyPercent();
        }
        return new SavingsSummaryDTO(month.toString(), environmentId, net, gross, storage, ipv4, adjustment,
                byCause(entries), potential, optimisable, efficiency, offHoursShare, actualsCheck(month, environmentId));
    }

    @Transactional(readOnly = true)
    public List<SavingsBreakdownRowDTO> byCause(YearMonth month, String environmentId) {
        return byCause(entries(month, environmentId));
    }

    @Transactional(readOnly = true)
    public List<SavingsBreakdownRowDTO> byEnvironment(YearMonth month) {
        List<SavingsLedgerEntry> entries = entries(month, null);
        Map<String, String> names = new HashMap<>();
        environmentRepository.findAllById(entries.stream().map(SavingsLedgerEntry::getEnvironmentId).distinct().toList())
                .forEach(e -> names.put(e.getEnvironmentId(), envName(e)));
        return breakdown(entries, SavingsLedgerEntry::getEnvironmentId, id -> names.getOrDefault(id, id));
    }

    @Transactional(readOnly = true)
    public List<SavingsBreakdownRowDTO> byTeam(YearMonth month) {
        return breakdown(entries(month, null), e -> e.getTeam() == null ? "Unassigned" : e.getTeam(), Function.identity());
    }

    @Transactional(readOnly = true)
    public List<EfficiencyTrendPointDTO> efficiencyTrend(int days, String environmentId) {
        LocalDate end = dayBoundary.today();
        LocalDate start = end.minusDays(Math.max(1, days) - 1L);
        List<EfficiencySnapshot> rows = environmentId == null
                ? efficiencyRepository.findByEnvironmentIdIsNullAndSnapshotDateBetweenOrderBySnapshotDateAsc(start, end)
                : efficiencyRepository.findByEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(environmentId, start, end);
        return rows.stream().map(s -> new EfficiencyTrendPointDTO(s.getSnapshotDate(), s.getEfficiencyPercent(),
                s.getPotentialSavings(), s.getOptimisableSpend())).toList();
    }

    /** The month's ledger rows as a workbook; a totals-by-cause block after the rows equals their sum. */
    @Transactional(readOnly = true)
    public byte[] exportMonth(YearMonth month) {
        List<SavingsLedgerEntry> entries = entries(month, null);
        Set<String> envIds = new HashSet<>();
        Set<String> vmIds = new HashSet<>();
        entries.forEach(e -> { envIds.add(e.getEnvironmentId()); vmIds.add(e.getVmId()); });
        Map<String, String> envNames = new HashMap<>();
        environmentRepository.findAllById(envIds).forEach(e -> envNames.put(e.getEnvironmentId(), envName(e)));
        Map<String, String> vmNames = new HashMap<>();
        vmRepository.findAllById(vmIds).forEach(v -> vmNames.put(v.getVmId(), vmName(v)));
        return excelExportService.toWorkbook("Savings Ledger " + month, EXPORT_HEADERS, exportRows(entries, envNames, vmNames));
    }

    static List<Object[]> exportRows(List<SavingsLedgerEntry> entries, Map<String, String> envNames,
                                     Map<String, String> vmNames) {
        List<Object[]> rows = new ArrayList<>();
        for (SavingsLedgerEntry e : entries) {
            rows.add(new Object[]{e.getLedgerDate().toString(), envNames.getOrDefault(e.getEnvironmentId(), e.getEnvironmentId()),
                    e.getTeam(), vmNames.getOrDefault(e.getVmId(), e.getVmId()), e.getCause().name(), e.getAvoidedHours(),
                    e.getHourlyRate(), e.getGrossSaving(), e.getResidualStorageCost(), e.getResidualIpv4Cost(),
                    e.getCommitmentCoveragePercent(), e.getCommitmentAdjustment(), e.getNetSaving(),
                    e.isPriceApproximate() ? "yes" : "no", e.getNote()});
        }
        rows.add(new Object[EXPORT_HEADERS.size()]);
        rows.add(totalRow("Totals by cause", null, null));
        for (SavingsBreakdownRowDTO c : byCause(entries)) {
            rows.add(totalRow(c.label(), c.hours(), c));
        }
        rows.add(totalRow("Total", sum(entries, SavingsLedgerEntry::getAvoidedHours),
                new SavingsBreakdownRowDTO("TOTAL", "Total", sum(entries, SavingsLedgerEntry::getNetSaving),
                        sum(entries, SavingsLedgerEntry::getGrossSaving), sum(entries, SavingsLedgerEntry::getAvoidedHours))));
        return rows;
    }

    private static Object[] totalRow(String label, BigDecimal hours, SavingsBreakdownRowDTO row) {
        Object[] r = new Object[EXPORT_HEADERS.size()];
        r[4] = label;
        if (row != null) {
            r[5] = hours;
            r[7] = row.gross();
            r[12] = row.net();
        }
        return r;
    }

    /** Estimated vs actual cost over the month's days that have actuals (target: within 15 %). */
    SavingsSummaryDTO.ActualsCheck actualsCheck(YearMonth month, String environmentId) {
        List<CostDailySnapshot> rows = costRepository.findWithActualBetween(Date.valueOf(month.atDay(1)),
                Date.valueOf(month.atEndOfMonth())).stream()
                .filter(c -> environmentId == null || environmentId.equals(c.getEnvironment().getEnvironmentId()))
                .toList();
        String label = "Estimate quality (target: within 15%)";
        if (rows.isEmpty()) return new SavingsSummaryDTO.ActualsCheck(0, null, null, null, false, label);
        BigDecimal estimated = rows.stream().map(c -> nz(c.getEstimatedCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = rows.stream().map(c -> nz(c.getActualCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
        int days = (int) rows.stream().map(CostDailySnapshot::getSnapshotDate).distinct().count();
        BigDecimal variance = actual.signum() == 0 ? null
                : estimated.subtract(actual).multiply(BigDecimal.valueOf(100)).divide(actual, 1, RoundingMode.HALF_UP);
        boolean within = variance != null && variance.abs().compareTo(TARGET_VARIANCE) <= 0;
        return new SavingsSummaryDTO.ActualsCheck(days, estimated.setScale(2, RoundingMode.HALF_UP),
                actual.setScale(2, RoundingMode.HALF_UP), variance, within, label);
    }

    private List<SavingsLedgerEntry> entries(YearMonth month, String environmentId) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return environmentId == null
                ? ledgerRepository.findByLedgerDateBetweenOrderByLedgerDateAsc(start, end)
                : ledgerRepository.findByEnvironmentIdAndLedgerDateBetweenOrderByLedgerDateAsc(environmentId, start, end);
    }

    private EfficiencySnapshot lastSnapshot(YearMonth month, String environmentId) {
        List<EfficiencySnapshot> rows = environmentId == null
                ? efficiencyRepository.findByEnvironmentIdIsNullAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        month.atDay(1), month.atEndOfMonth())
                : efficiencyRepository.findByEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        environmentId, month.atDay(1), month.atEndOfMonth());
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    static List<SavingsBreakdownRowDTO> byCause(List<SavingsLedgerEntry> entries) {
        Map<SavingsCause, List<SavingsLedgerEntry>> grouped = new EnumMap<>(SavingsCause.class);
        entries.forEach(e -> grouped.computeIfAbsent(e.getCause(), k -> new ArrayList<>()).add(e));
        return grouped.entrySet().stream()
                .map(g -> row(g.getKey().name(), label(g.getKey()), g.getValue()))
                .sorted(Comparator.comparing(SavingsBreakdownRowDTO::net).reversed())
                .toList();
    }

    private static List<SavingsBreakdownRowDTO> breakdown(List<SavingsLedgerEntry> entries,
                                                          Function<SavingsLedgerEntry, String> key,
                                                          Function<String, String> label) {
        return entries.stream().collect(Collectors.groupingBy(key)).entrySet().stream()
                .map(g -> row(g.getKey(), label.apply(g.getKey()), g.getValue()))
                .sorted(Comparator.comparing(SavingsBreakdownRowDTO::net).reversed())
                .toList();
    }

    private static SavingsBreakdownRowDTO row(String key, String label, List<SavingsLedgerEntry> entries) {
        return new SavingsBreakdownRowDTO(key, label, sum(entries, SavingsLedgerEntry::getNetSaving),
                sum(entries, SavingsLedgerEntry::getGrossSaving), sum(entries, SavingsLedgerEntry::getAvoidedHours));
    }

    static String label(SavingsCause cause) {
        return switch (cause) {
            case SCHEDULE -> "Schedules";
            case LEASE -> "Leases";
            case IDLE_STOP -> "Idle stops";
            case MANUAL_STOP -> "Manual stops";
            case RIGHTSIZING -> "Rightsizing";
            case OTHER -> "Other";
        };
    }

    private static BigDecimal sum(List<SavingsLedgerEntry> entries, Function<SavingsLedgerEntry, BigDecimal> f) {
        return entries.stream().map(f).map(SavingsReportService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String envName(Environment e) {
        return e.getDisplayName() != null && !e.getDisplayName().isBlank() ? e.getDisplayName() : e.getName();
    }

    private static String vmName(Vm v) {
        return v.getDisplayName() != null && !v.getDisplayName().isBlank() ? v.getDisplayName() : v.getName();
    }
}
