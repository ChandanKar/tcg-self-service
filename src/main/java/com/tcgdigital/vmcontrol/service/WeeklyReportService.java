package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.WeeklyCostReportRowDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationReportType;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Weekly digest reports (Cost / Idle Waste / Rightsizing) — one Excel attachment per recipient,
 * consolidated across every environment that recipient is scoped to, rather than one email per
 * environment. Admin sees every environment; an env-admin sees only environments where they hold
 * an ADMIN-level {@link EnvironmentAccessService#getAdministeredEnvironmentIds grant}. The bell
 * receipt always fires (via {@link NotificationService#notifyWeeklyReportSent}), independent of
 * whether that report's own email toggle is on — matching every other notification type.
 */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    @Value("${notification.email.weekly-cost-report.enabled:false}")
    private boolean weeklyCostReportEmailEnabled;
    @Value("${notification.email.weekly-idle-waste-report.enabled:false}")
    private boolean weeklyIdleWasteReportEmailEnabled;
    @Value("${notification.email.weekly-rightsizing-report.enabled:false}")
    private boolean weeklyRightsizingReportEmailEnabled;

    private final CostDailySnapshotRepository costDailySnapshotRepository;
    private final EnvironmentRepository environmentRepository;
    private final EnvironmentAccessService environmentAccessService;
    private final UserRepository userRepository;
    private final CostEstimationService costEstimationService;
    private final WeeklySnapshotService weeklySnapshotService;
    private final ExcelExportService excelExportService;
    private final EmailService emailService;
    private final NotificationService notificationService;

    public WeeklyReportService(CostDailySnapshotRepository costDailySnapshotRepository,
                                EnvironmentRepository environmentRepository,
                                EnvironmentAccessService environmentAccessService,
                                UserRepository userRepository,
                                CostEstimationService costEstimationService,
                                WeeklySnapshotService weeklySnapshotService,
                                ExcelExportService excelExportService,
                                EmailService emailService,
                                NotificationService notificationService) {
        this.costDailySnapshotRepository = costDailySnapshotRepository;
        this.environmentRepository = environmentRepository;
        this.environmentAccessService = environmentAccessService;
        this.userRepository = userRepository;
        this.costEstimationService = costEstimationService;
        this.weeklySnapshotService = weeklySnapshotService;
        this.excelExportService = excelExportService;
        this.emailService = emailService;
        this.notificationService = notificationService;
    }

    // ============= Weekly Cost Report =============

    public void sendWeeklyCostReport() {
        LocalDate today = LocalDate.now();
        Date currentStart = Date.valueOf(today.minusDays(7));
        Date currentEnd = Date.valueOf(today);
        Date previousStart = Date.valueOf(today.minusDays(14));
        Date previousEnd = currentStart;

        List<WeeklyCostReportRowDTO> allRows = buildCostRows(currentStart, currentEnd, previousStart, previousEnd);
        if (allRows.isEmpty()) {
            log.info("Weekly cost report: no cost snapshots in the reporting window ({} to {}), skipping send.",
                    currentStart, currentEnd);
            return;
        }

        String subject = "Weekly Cost Report — " + currentStart + " to " + today.minusDays(1);
        byte[] allEnvironmentsWorkbook = buildCostWorkbook(allRows);

        for (User admin : userRepository.findByAdminTrueAndIsActiveTrue()) {
            sendCostReportToRecipient(admin, allRows, allEnvironmentsWorkbook, subject, "all environments");
        }

        for (User envAdmin : userRepository.findByEnvAdminTrueAndIsActiveTrue()) {
            List<String> administeredIds = environmentAccessService.getAdministeredEnvironmentIds(envAdmin.getUserId());
            if (administeredIds.isEmpty()) {
                continue;
            }
            List<WeeklyCostReportRowDTO> subset = allRows.stream()
                    .filter(row -> administeredIds.contains(row.environmentId()))
                    .toList();
            if (subset.isEmpty()) {
                continue;
            }
            sendCostReportToRecipient(envAdmin, subset, buildCostWorkbook(subset), subject, "your environments");
        }
    }

    /** Package-private so tests can drive explicit date windows rather than depending on wall-clock time. */
    List<WeeklyCostReportRowDTO> buildCostRows(Date currentStart, Date currentEnd, Date previousStart, Date previousEnd) {
        Map<String, CostDailySnapshotRepository.EnvironmentCostTotal> currentTotals =
                costDailySnapshotRepository.sumByEnvironmentBetween(currentStart, currentEnd).stream()
                        .collect(Collectors.toMap(CostDailySnapshotRepository.EnvironmentCostTotal::getEnvironmentId, t -> t));
        Map<String, CostDailySnapshotRepository.EnvironmentCostTotal> previousTotals =
                costDailySnapshotRepository.sumByEnvironmentBetween(previousStart, previousEnd).stream()
                        .collect(Collectors.toMap(CostDailySnapshotRepository.EnvironmentCostTotal::getEnvironmentId, t -> t));
        Map<String, String> environmentNames = environmentRepository.findAll().stream()
                .collect(Collectors.toMap(Environment::getEnvironmentId, Environment::getDisplayName));

        List<WeeklyCostReportRowDTO> rows = new ArrayList<>();
        for (Map.Entry<String, CostDailySnapshotRepository.EnvironmentCostTotal> entry : currentTotals.entrySet()) {
            String environmentId = entry.getKey();
            BigDecimal estimated = nullToZero(entry.getValue().getTotalEstimatedCost());
            BigDecimal actual = entry.getValue().getTotalActualCost();

            CostDailySnapshotRepository.EnvironmentCostTotal previous = previousTotals.get(environmentId);
            BigDecimal previousEstimated = previous != null ? nullToZero(previous.getTotalEstimatedCost()) : null;
            BigDecimal deltaPercent = percentDelta(estimated, previousEstimated);

            String name = environmentNames.getOrDefault(environmentId, environmentId);
            rows.add(new WeeklyCostReportRowDTO(environmentId, name, estimated, actual, deltaPercent));
        }

        rows.sort(Comparator.comparing(WeeklyCostReportRowDTO::estimatedCost).reversed());
        return rows;
    }

    private void sendCostReportToRecipient(User recipient, List<WeeklyCostReportRowDTO> rows, byte[] workbook,
                                           String subject, String scopeLabel) {
        notificationService.notifyWeeklyReportSent(recipient.getUserId(), NotificationType.WEEKLY_COST_REPORT,
                subject, "Your weekly cost report (" + scopeLabel + ", " + rows.size() + " environment(s)) is ready.");

        if (!weeklyCostReportEmailEnabled || isBlank(recipient.getEmail())) {
            return;
        }
        String bodyHtml = EmailTemplates.digestSummary(subject,
                "Weekly cost report for " + scopeLabel + " (" + rows.size() + " environment(s)).",
                List.of(), List.of());
        emailService.sendHtml(List.of(recipient.getEmail()), subject, bodyHtml, "weekly-cost-report.xlsx", workbook);
    }

    private byte[] buildCostWorkbook(List<WeeklyCostReportRowDTO> rows) {
        List<Object[]> data = rows.stream()
                .map(row -> new Object[]{
                        row.environmentName(),
                        row.estimatedCost(),
                        row.actualCost() != null ? row.actualCost() : "N/A",
                        row.weekOverWeekChangePercent() != null ? row.weekOverWeekChangePercent() + "%" : "N/A"
                })
                .toList();
        return excelExportService.toWorkbook("Weekly Cost Report",
                List.of("Environment", "Estimated Cost", "Actual Cost", "Week-over-Week Change"), data);
    }

    // ============= Weekly Idle Waste Report (Admin only) =============

    public void sendWeeklyIdleWasteReport() {
        LocalDate today = LocalDate.now();
        Date currentSnapshotDate = Date.valueOf(today);
        Date previousSnapshotDate = Date.valueOf(today.minusWeeks(1));

        List<IdleWasteRowDTO> allWaste = costEstimationService.getIdleWaste(PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        if (allWaste.isEmpty()) {
            log.info("Weekly idle waste report: no idle-waste rows found, skipping send.");
            return;
        }

        Map<String, BigDecimal> totalsByEnvironment = new LinkedHashMap<>();
        for (IdleWasteRowDTO row : allWaste) {
            totalsByEnvironment.merge(row.environmentId(), nullToZero(row.monthlyIdleCost()), BigDecimal::add);
        }
        BigDecimal grandTotal = totalsByEnvironment.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> previousTotals = weeklySnapshotService.getPreviousWeekTotals(
                WeeklyOptimizationReportType.IDLE_WASTE, previousSnapshotDate);
        BigDecimal previousGrandTotal = previousTotals.isEmpty() ? null : sum(previousTotals.values());
        BigDecimal deltaPercent = percentDelta(grandTotal, previousGrandTotal);

        weeklySnapshotService.captureSnapshot(WeeklyOptimizationReportType.IDLE_WASTE, currentSnapshotDate, totalsByEnvironment);

        String subject = "Weekly Idle Waste Report — " + today;
        byte[] workbook = buildIdleWasteWorkbook(allWaste);
        String deltaSuffix = deltaPercent != null ? " (" + formatDelta(deltaPercent) + " vs last week)" : "";

        for (User admin : userRepository.findByAdminTrueAndIsActiveTrue()) {
            notificationService.notifyWeeklyReportSent(admin.getUserId(), NotificationType.WEEKLY_IDLE_WASTE_REPORT,
                    subject, "Your weekly idle waste report is ready. Total idle cost: " + grandTotal + deltaSuffix + ".");

            if (!weeklyIdleWasteReportEmailEnabled || isBlank(admin.getEmail())) {
                continue;
            }
            String bodyHtml = EmailTemplates.digestSummary(subject,
                    "Total idle-waste cost this week: " + grandTotal + deltaSuffix + ".", List.of(), List.of());
            emailService.sendHtml(List.of(admin.getEmail()), subject, bodyHtml, "weekly-idle-waste-report.xlsx", workbook);
        }
    }

    private byte[] buildIdleWasteWorkbook(List<IdleWasteRowDTO> rows) {
        List<Object[]> data = rows.stream()
                .map(row -> new Object[]{
                        row.environmentName(),
                        row.groupName(),
                        row.vmName(),
                        row.monthlyCost() != null ? row.monthlyCost() : "N/A",
                        row.monthlyIdleCost() != null ? row.monthlyIdleCost() : "N/A",
                        row.idleDurationMinutes() != null ? row.idleDurationMinutes() : "N/A"
                })
                .toList();
        return excelExportService.toWorkbook("Weekly Idle Waste Report",
                List.of("Environment", "Group", "VM", "Monthly Cost", "Monthly Idle Cost", "Idle Duration (min)"), data);
    }

    // ============= Weekly Rightsizing Report (Admin + Env Admin) =============

    public void sendWeeklyRightsizingReport() {
        LocalDate today = LocalDate.now();
        Date currentSnapshotDate = Date.valueOf(today);
        Date previousSnapshotDate = Date.valueOf(today.minusWeeks(1));

        List<RightsizingCandidateDTO> allCandidates = costEstimationService
                .getRightsizingCandidates(PageRequest.of(0, Integer.MAX_VALUE)).getContent();
        if (allCandidates.isEmpty()) {
            log.info("Weekly rightsizing report: no candidates found, skipping send.");
            return;
        }

        Map<String, BigDecimal> totalsByEnvironment = new LinkedHashMap<>();
        for (RightsizingCandidateDTO candidate : allCandidates) {
            totalsByEnvironment.merge(candidate.environmentId(), nullToZero(candidate.estimatedMonthlySavings()), BigDecimal::add);
        }

        Map<String, BigDecimal> previousTotals = weeklySnapshotService.getPreviousWeekTotals(
                WeeklyOptimizationReportType.RIGHTSIZING, previousSnapshotDate);

        weeklySnapshotService.captureSnapshot(WeeklyOptimizationReportType.RIGHTSIZING, currentSnapshotDate, totalsByEnvironment);

        String subject = "Weekly Rightsizing Report — " + today;

        BigDecimal globalCurrentTotal = sum(totalsByEnvironment.values());
        BigDecimal globalPreviousTotal = previousTotals.isEmpty() ? null : sum(previousTotals.values());
        BigDecimal globalDeltaPercent = percentDelta(globalCurrentTotal, globalPreviousTotal);

        byte[] allEnvironmentsWorkbook = buildRightsizingWorkbook(allCandidates);
        for (User admin : userRepository.findByAdminTrueAndIsActiveTrue()) {
            sendRightsizingReportToRecipient(admin, allCandidates, allEnvironmentsWorkbook, subject,
                    "all environments", globalCurrentTotal, globalDeltaPercent);
        }

        for (User envAdmin : userRepository.findByEnvAdminTrueAndIsActiveTrue()) {
            List<String> administeredIds = environmentAccessService.getAdministeredEnvironmentIds(envAdmin.getUserId());
            if (administeredIds.isEmpty()) {
                continue;
            }
            List<RightsizingCandidateDTO> subset = allCandidates.stream()
                    .filter(c -> administeredIds.contains(c.environmentId()))
                    .toList();
            if (subset.isEmpty()) {
                continue;
            }

            BigDecimal subsetCurrentTotal = administeredIds.stream()
                    .map(id -> totalsByEnvironment.getOrDefault(id, BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean hasAnyPriorData = administeredIds.stream().anyMatch(previousTotals::containsKey);
            BigDecimal subsetPreviousTotal = hasAnyPriorData
                    ? administeredIds.stream().map(id -> previousTotals.getOrDefault(id, BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                    : null;
            BigDecimal subsetDeltaPercent = percentDelta(subsetCurrentTotal, subsetPreviousTotal);

            sendRightsizingReportToRecipient(envAdmin, subset, buildRightsizingWorkbook(subset), subject,
                    "your environments", subsetCurrentTotal, subsetDeltaPercent);
        }
    }

    private void sendRightsizingReportToRecipient(User recipient, List<RightsizingCandidateDTO> rows, byte[] workbook,
                                                  String subject, String scopeLabel,
                                                  BigDecimal totalSavings, BigDecimal deltaPercent) {
        String deltaSuffix = deltaPercent != null ? " (" + formatDelta(deltaPercent) + " vs last week)" : "";
        notificationService.notifyWeeklyReportSent(recipient.getUserId(), NotificationType.WEEKLY_RIGHTSIZING_REPORT,
                subject, "Your weekly rightsizing report (" + scopeLabel + ", " + rows.size() + " candidate(s)) is ready. " +
                        "Potential savings: " + totalSavings + deltaSuffix + ".");

        if (!weeklyRightsizingReportEmailEnabled || isBlank(recipient.getEmail())) {
            return;
        }
        String bodyHtml = EmailTemplates.digestSummary(subject,
                "Weekly rightsizing report for " + scopeLabel + " — potential savings " + totalSavings + deltaSuffix + ".",
                List.of(), List.of());
        emailService.sendHtml(List.of(recipient.getEmail()), subject, bodyHtml, "weekly-rightsizing-report.xlsx", workbook);
    }

    private byte[] buildRightsizingWorkbook(List<RightsizingCandidateDTO> rows) {
        List<Object[]> data = rows.stream()
                .map(row -> new Object[]{
                        row.environmentName(),
                        row.vmName(),
                        row.direction(),
                        row.currentInstanceType(),
                        row.suggestedInstanceType(),
                        row.source(),
                        row.estimatedMonthlySavings() != null ? row.estimatedMonthlySavings() : "N/A"
                })
                .toList();
        return excelExportService.toWorkbook("Weekly Rightsizing Report",
                List.of("Environment", "VM", "Direction", "Current Instance", "Suggested Instance", "Source", "Est. Monthly Savings"),
                data);
    }

    // ============= Shared helpers =============

    /** @return null when there's no positive prior value to compare against — never fabricated as zero. */
    private BigDecimal percentDelta(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    private String formatDelta(BigDecimal deltaPercent) {
        String sign = deltaPercent.compareTo(BigDecimal.ZERO) > 0 ? "+" : "";
        return sign + deltaPercent + "%";
    }

    private BigDecimal sum(java.util.Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
