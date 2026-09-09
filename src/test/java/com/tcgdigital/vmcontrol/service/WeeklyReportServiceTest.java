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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklyReportServiceTest {

    @Mock private CostDailySnapshotRepository costDailySnapshotRepository;
    @Mock private EnvironmentRepository environmentRepository;
    @Mock private EnvironmentAccessService environmentAccessService;
    @Mock private UserRepository userRepository;
    @Mock private CostEstimationService costEstimationService;
    @Mock private WeeklySnapshotService weeklySnapshotService;
    @Mock private ExcelExportService excelExportService;
    @Mock private EmailService emailService;
    @Mock private NotificationService notificationService;

    private WeeklyReportService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(costDailySnapshotRepository, environmentRepository,
                environmentAccessService, userRepository, costEstimationService, weeklySnapshotService,
                excelExportService, emailService, notificationService);
        lenient().when(excelExportService.toWorkbook(any(), any(), any())).thenReturn(new byte[]{1});
        lenient().when(costEstimationService.getUptimeHoursByEnvironment(any(), any())).thenReturn(Map.of());
    }

    // ---- Weekly Cost Report ----

    @Test
    void buildCostRows_deltaPercentCorrectForKnownBeforeAfter() {
        Date currentStart = Date.valueOf(LocalDate.of(2026, 7, 20));
        Date currentEnd = Date.valueOf(LocalDate.of(2026, 7, 27));
        Date previousStart = Date.valueOf(LocalDate.of(2026, 7, 13));
        Date previousEnd = currentStart;

        var currentTotal = costTotal("env-A", "110.00", null);
        var previousTotal = costTotal("env-A", "100.00", null);
        when(costDailySnapshotRepository.sumByEnvironmentBetween(currentStart, currentEnd))
                .thenReturn(List.of(currentTotal));
        when(costDailySnapshotRepository.sumByEnvironmentBetween(previousStart, previousEnd))
                .thenReturn(List.of(previousTotal));
        when(environmentRepository.findAll()).thenReturn(List.of(environment("env-A", "Env A")));

        List<WeeklyCostReportRowDTO> rows = service.buildCostRows(currentStart, currentEnd, previousStart, previousEnd);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("10.0").compareTo(rows.get(0).weekOverWeekChangePercent()));
    }

    @Test
    void buildCostRows_carriesPerEnvironmentUptimeHoursRoundedToOneDecimal() {
        Date currentStart = Date.valueOf(LocalDate.of(2026, 7, 20));
        Date currentEnd = Date.valueOf(LocalDate.of(2026, 7, 27));
        Date previousStart = Date.valueOf(LocalDate.of(2026, 7, 13));
        Date previousEnd = currentStart;

        var totalA = costTotal("env-A", "110.00", null);
        var totalB = costTotal("env-B", "40.00", null);
        when(costDailySnapshotRepository.sumByEnvironmentBetween(currentStart, currentEnd))
                .thenReturn(List.of(totalA, totalB));
        when(costDailySnapshotRepository.sumByEnvironmentBetween(previousStart, previousEnd))
                .thenReturn(List.of());
        when(environmentRepository.findAll())
                .thenReturn(List.of(environment("env-A", "Env A"), environment("env-B", "Env B")));
        when(costEstimationService.getUptimeHoursByEnvironment(any(), any()))
                .thenReturn(Map.of("env-A", new BigDecimal("123.456")));

        List<WeeklyCostReportRowDTO> rows = service.buildCostRows(currentStart, currentEnd, previousStart, previousEnd);

        Map<String, BigDecimal> uptimeByEnv = rows.stream()
                .collect(java.util.stream.Collectors.toMap(WeeklyCostReportRowDTO::environmentId,
                        WeeklyCostReportRowDTO::uptimeHours));
        assertEquals(0, new BigDecimal("123.5").compareTo(uptimeByEnv.get("env-A")));
        // an environment with no uptime entry falls back to 0, never null
        assertEquals(0, BigDecimal.ZERO.compareTo(uptimeByEnv.get("env-B")));
    }

    @Test
    void buildCostRows_noPreviousWeekData_deltaIsNullNotDivideByZero() {
        Date currentStart = Date.valueOf(LocalDate.of(2026, 7, 20));
        Date currentEnd = Date.valueOf(LocalDate.of(2026, 7, 27));
        Date previousStart = Date.valueOf(LocalDate.of(2026, 7, 13));
        Date previousEnd = currentStart;

        var currentTotal = costTotal("env-A", "110.00", null);
        when(costDailySnapshotRepository.sumByEnvironmentBetween(currentStart, currentEnd))
                .thenReturn(List.of(currentTotal));
        when(costDailySnapshotRepository.sumByEnvironmentBetween(previousStart, previousEnd))
                .thenReturn(List.of());
        when(environmentRepository.findAll()).thenReturn(List.of(environment("env-A", "Env A")));

        List<WeeklyCostReportRowDTO> rows = service.buildCostRows(currentStart, currentEnd, previousStart, previousEnd);

        assertNull(rows.get(0).weekOverWeekChangePercent());
    }

    @Test
    void sendWeeklyCostReport_adminGetsAllEnvironments_envAdminGetsOnlySubset() {
        User admin = user("user-admin", "admin@tcg.com", true, false);
        User envAdmin = user("user-envadmin", "envadmin@tcg.com", false, true);

        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(admin));
        when(userRepository.findByEnvAdminTrueAndIsActiveTrue()).thenReturn(List.of(envAdmin));
        when(environmentAccessService.getAdministeredEnvironmentIds("user-envadmin")).thenReturn(List.of("env-A"));

        var totalA = costTotal("env-A", "100.00", null);
        var totalB = costTotal("env-B", "200.00", null);
        when(costDailySnapshotRepository.sumByEnvironmentBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(totalA, totalB))
                .thenReturn(List.of());
        when(environmentRepository.findAll()).thenReturn(List.of(environment("env-A", "Env A"), environment("env-B", "Env B")));

        service.sendWeeklyCostReport();

        verify(notificationService).notifyWeeklyReportSent(eq("user-admin"), eq(NotificationType.WEEKLY_COST_REPORT),
                anyString(), contains("2 environment"));
        verify(notificationService).notifyWeeklyReportSent(eq("user-envadmin"), eq(NotificationType.WEEKLY_COST_REPORT),
                anyString(), contains("1 environment"));
    }

    @Test
    void sendWeeklyCostReport_bellFiresRegardlessOfEmailFlag() {
        ReflectionTestUtils.setField(service, "weeklyCostReportEmailEnabled", false);
        User admin = user("user-admin", "admin@tcg.com", true, false);
        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(admin));
        when(userRepository.findByEnvAdminTrueAndIsActiveTrue()).thenReturn(List.of());
        var totalA = costTotal("env-A", "100.00", null);
        when(costDailySnapshotRepository.sumByEnvironmentBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(totalA))
                .thenReturn(List.of());
        when(environmentRepository.findAll()).thenReturn(List.of(environment("env-A", "Env A")));

        service.sendWeeklyCostReport();

        verify(notificationService).notifyWeeklyReportSent(eq("user-admin"), eq(NotificationType.WEEKLY_COST_REPORT), any(), any());
        verifyNoInteractions(emailService);
    }

    // ---- Weekly Idle Waste Report ----

    @Test
    void sendWeeklyIdleWasteReport_totalDeltaCorrectAgainstMockedPreviousSnapshot() {
        User admin = user("user-admin", "admin@tcg.com", true, false);
        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(admin));
        when(costEstimationService.getIdleWaste(any())).thenReturn(new PageImpl<>(
                List.of(idleWasteRow("env-A", "60.00"), idleWasteRow("env-B", "40.00"))));
        when(weeklySnapshotService.getPreviousWeekTotals(eq(WeeklyOptimizationReportType.IDLE_WASTE), any(Date.class)))
                .thenReturn(Map.of("env-A", new BigDecimal("50.00"), "env-B", new BigDecimal("30.00")));

        service.sendWeeklyIdleWasteReport();

        // current grand total = 100.00, previous = 80.00 -> +25.0%
        verify(notificationService).notifyWeeklyReportSent(eq("user-admin"), eq(NotificationType.WEEKLY_IDLE_WASTE_REPORT),
                anyString(), contains("+25.0%"));
        verify(weeklySnapshotService).captureSnapshot(eq(WeeklyOptimizationReportType.IDLE_WASTE), any(Date.class), anyMap());
    }

    @Test
    void sendWeeklyIdleWasteReport_coldStart_sendsWithoutThrowingAndOmitsDelta() {
        User admin = user("user-admin", "admin@tcg.com", true, false);
        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(admin));
        when(costEstimationService.getIdleWaste(any())).thenReturn(new PageImpl<>(List.of(idleWasteRow("env-A", "60.00"))));
        when(weeklySnapshotService.getPreviousWeekTotals(any(), any())).thenReturn(Map.of());

        assertDoesNotThrow(() -> service.sendWeeklyIdleWasteReport());

        verify(notificationService).notifyWeeklyReportSent(eq("user-admin"), eq(NotificationType.WEEKLY_IDLE_WASTE_REPORT),
                anyString(), argThat(message -> !message.contains("vs last week")));
    }

    @Test
    void sendWeeklyIdleWasteReport_neverConsultsEnvAdmins() {
        User admin = user("user-admin", "admin@tcg.com", true, false);
        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(admin));
        when(costEstimationService.getIdleWaste(any())).thenReturn(new PageImpl<>(List.of(idleWasteRow("env-A", "10.00"))));
        when(weeklySnapshotService.getPreviousWeekTotals(any(), any())).thenReturn(Map.of());

        service.sendWeeklyIdleWasteReport();

        verify(userRepository, never()).findByEnvAdminTrueAndIsActiveTrue();
        verify(environmentAccessService, never()).getAdministeredEnvironmentIds(any());
    }

    // ---- Weekly Rightsizing Report ----

    @Test
    void sendWeeklyRightsizingReport_envAdminSubsetDeltaUsesOnlyTheirEnvironments() {
        User envAdmin = user("user-envadmin", "envadmin@tcg.com", false, true);
        when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of());
        when(userRepository.findByEnvAdminTrueAndIsActiveTrue()).thenReturn(List.of(envAdmin));
        when(environmentAccessService.getAdministeredEnvironmentIds("user-envadmin")).thenReturn(List.of("env-A"));

        when(costEstimationService.getRightsizingCandidates(any())).thenReturn(new PageImpl<>(
                List.of(rightsizingCandidate("env-A", "50.00"), rightsizingCandidate("env-B", "999.00"))));
        // previous week: env-A=40.00, env-B=10.00 — env-B must not pollute the env-admin's subset delta
        when(weeklySnapshotService.getPreviousWeekTotals(eq(WeeklyOptimizationReportType.RIGHTSIZING), any(Date.class)))
                .thenReturn(Map.of("env-A", new BigDecimal("40.00"), "env-B", new BigDecimal("10.00")));

        service.sendWeeklyRightsizingReport();

        // env-admin subset: current=50.00, previous=40.00 -> +25.0%
        verify(notificationService).notifyWeeklyReportSent(eq("user-envadmin"), eq(NotificationType.WEEKLY_RIGHTSIZING_REPORT),
                anyString(), contains("+25.0%"));
    }

    // ---- fixtures ----

    private static User user(String id, String email, boolean admin, boolean envAdmin) {
        User u = new User(id);
        u.setEmail(email);
        u.setDisplayName(id);
        u.setIsActive(true);
        u.setAdmin(admin);
        u.setEnvAdmin(envAdmin);
        return u;
    }

    private static Environment environment(String id, String displayName) {
        Environment env = new Environment(id);
        env.setDisplayName(displayName);
        return env;
    }

    private static CostDailySnapshotRepository.EnvironmentCostTotal costTotal(String environmentId, String estimated, String actual) {
        CostDailySnapshotRepository.EnvironmentCostTotal total = mock(CostDailySnapshotRepository.EnvironmentCostTotal.class);
        lenient().when(total.getEnvironmentId()).thenReturn(environmentId);
        lenient().when(total.getTotalEstimatedCost()).thenReturn(new BigDecimal(estimated));
        lenient().when(total.getTotalActualCost()).thenReturn(actual == null ? null : new BigDecimal(actual));
        return total;
    }

    private static IdleWasteRowDTO idleWasteRow(String environmentId, String monthlyIdleCost) {
        return new IdleWasteRowDTO("vm-1", "VM 1", environmentId, "Env", "Group",
                BigDecimal.TEN, true, 60, null, BigDecimal.ZERO, new BigDecimal(monthlyIdleCost));
    }

    private static RightsizingCandidateDTO rightsizingCandidate(String environmentId, String estimatedMonthlySavings) {
        return new RightsizingCandidateDTO("vm-1", "VM 1", environmentId, "Env",
                "t3.large", "t3.medium", new BigDecimal("5"), new BigDecimal("10"),
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal(estimatedMonthlySavings),
                true, "cpu-threshold-rule", null, "SCALE_DOWN", "STOPPED");
    }
}
