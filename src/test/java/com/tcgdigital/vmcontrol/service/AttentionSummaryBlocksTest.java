package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AttentionSummaryDTO;
import com.tcgdigital.vmcontrol.dto.BudgetDTO;
import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.repository.ApprovalRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** U05-T04: budgets near their limit and late scheduled jobs, from their sources' rows. */
class AttentionSummaryBlocksTest {

    private static BudgetDTO budget(String name, String percentUsed, String forecastPercent, boolean enabled) {
        return new BudgetDTO("b-" + name, BudgetScopeType.ENVIRONMENT, "env-" + name, name, new BigDecimal("1000"),
                List.of(50, 90, 100), false, BudgetActionType.NONE, enabled, null, "2026-10", null, null,
                percentUsed == null ? null : new BigDecimal(percentUsed), null,
                forecastPercent == null ? null : new BigDecimal(forecastPercent), "admin-001", null, null, null, null);
    }

    private static DashboardSummaryDTO.SchedulerHealthDTO job(String label, String status, Long freshness, long interval) {
        return new DashboardSummaryDTO.SchedulerHealthDTO(label, status, null, null, freshness, null, interval);
    }

    private static AttentionSummaryService service(List<BudgetDTO> budgets, List<DashboardSummaryDTO.SchedulerHealthDTO> jobs,
                                                   boolean costSnapshots) {
        BudgetService budgetService = mock(BudgetService.class);
        when(budgetService.list()).thenReturn(budgets);
        DashboardSummaryService dashboard = mock(DashboardSummaryService.class);
        when(dashboard.buildSchedulerHealth(anyBoolean())).thenReturn(jobs);
        StateSyncService sync = mock(StateSyncService.class);
        StateSyncStatusDTO status = new StateSyncStatusDTO();
        status.setLastSyncTime(Timestamp.valueOf("2026-10-20 09:55:00"));
        when(sync.getSyncStatus()).thenReturn(status);
        return new AttentionSummaryService(mock(EnvironmentAccessRequestRepository.class), mock(ApprovalRequestRepository.class),
                mock(OperationExecutionRepository.class), mock(VmRepository.class), mock(VmStateHistoryRepository.class),
                mock(EnvironmentRepository.class), budgetService, dashboard, sync, 30, costSnapshots);
    }

    @Test
    void budgetsAt90PercentOrForecastPastTheirAmountAreNearTheLimit() {
        AttentionSummaryDTO s = service(List.of(
                budget("Payments", "92.5", "105", true),
                budget("CRM", "60", "101", true),
                budget("Analytics", "97", "99", true),
                budget("Quiet", "40", "70", true),
                budget("Off", "99", "120", false)), List.of(), true).summary();

        assertThat(s.budgets().nearLimit()).isEqualTo(3);
        assertThat(s.budgets().top()).isEqualTo(new AttentionSummaryDTO.BudgetUse("Analytics", new BigDecimal("97")));
    }

    @Test
    void withCostSnapshotsOffNoBudgetIsNearItsLimit() {
        AttentionSummaryDTO s = service(List.of(budget("Payments", "95", "110", true)), List.of(), false).summary();

        assertThat(s.budgets()).isEqualTo(new AttentionSummaryDTO.Budgets(0, null));
    }

    @Test
    void lateStaleAndNeverRunJobsCountSwitchedOffOnesDoNot() {
        AttentionSummaryDTO s = service(List.of(), List.of(
                job("State sync", "HEALTHY", 120L, 300),
                job("Inventory sync", "LATE", 600L, 300),
                job("Cost snapshot", "STALE", 400_000L, 86_400),
                job("Lock expiry", "NEVER", null, 60),
                job("Retention purge", "DISABLED", null, 86_400),
                job("Metrics", "RUNNING", 10L, 300)), true).summary();

        assertThat(s.lateJobs().count()).isEqualTo(3);
        assertThat(s.lateJobs().worst()).isEqualTo(new AttentionSummaryDTO.LateJob("Cost snapshot", 400_000L - 86_400));
        assertThat(s.health().jobsEnabled()).isEqualTo(5);
        assertThat(s.health().jobsOnTime()).isEqualTo(2);
        assertThat(s.health().lastStateSyncAt()).isEqualTo(Timestamp.valueOf("2026-10-20 09:55:00"));
    }
}
