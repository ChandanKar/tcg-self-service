package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.repository.BudgetAlertRepository;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One budget's failure, or a failing notification, never stops the evaluation (E20-T02). */
class BudgetEvaluationLoopTest {

    private final BudgetRepository budgets = mock(BudgetRepository.class);
    private final BudgetAlertRepository alerts = mock(BudgetAlertRepository.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final EnvironmentRepository environments = mock(EnvironmentRepository.class);
    private final BudgetEvaluationService evaluation =
            new BudgetEvaluationService(budgets, alerts, budgetService, notifications, environments);

    private static Budget budget(String id) {
        Budget b = new Budget();
        b.setBudgetId(id);
        b.setScopeType(BudgetScopeType.ENVIRONMENT);
        b.setScopeId("env-" + id);
        b.setMonthlyAmount(new BigDecimal("100.00"));
        b.setForecastAlert(false);
        return b;
    }

    @Test
    void aFailingBudgetAndAFailingNotificationDoNotStopTheOthers() {
        ReflectionTestUtils.setField(evaluation, "clock",
                Clock.fixed(LocalDate.of(2026, 9, 11).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        Budget broken = budget("broken");
        Budget fine = budget("fine");
        when(budgets.findByEnabledTrue()).thenReturn(List.of(broken, fine));
        when(environments.findById(anyString())).thenReturn(Optional.empty());
        when(budgetService.environmentIds(any())).thenReturn(List.of("env"));
        when(budgetService.spend(eq(broken), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(budgetService.spend(eq(fine), any(), any())).thenReturn(new BudgetService.Spend(YearMonth.of(2026, 9),
                LocalDate.of(2026, 9, 10), new BigDecimal("80.00"), null));
        doThrow(new RuntimeException("mail down")).when(notifications)
                .notifyBudgetThreshold(anyList(), anyString(), anyBoolean(), anyString(), any(), eq(50), any(), any());

        // 50 and 75 fire for "fine" even though the 50% notification throws.
        assertThat(evaluation.evaluateAll()).isEqualTo(2);
        verify(notifications).notifyBudgetThreshold(anyList(), eq("fine"), anyBoolean(), anyString(), any(), eq(75), any(), any());
        verify(budgetService).spend(eq(fine), eq(YearMonth.of(2026, 9)), eq(LocalDate.of(2026, 9, 10)));
        verify(alerts, org.mockito.Mockito.times(2)).saveAndFlush(any());
        verify(notifications, org.mockito.Mockito.never())
                .notifyBudgetForecast(anyList(), anyString(), anyBoolean(), anyString(), any(), any(), any());
    }
}
