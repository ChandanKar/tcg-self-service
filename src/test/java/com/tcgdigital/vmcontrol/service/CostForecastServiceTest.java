package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostForecastDTO;
import com.tcgdigital.vmcontrol.dto.CostForecastPointDTO;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostForecastServiceTest {

    @Mock private CostDailySnapshotRepository costDailySnapshotRepository;

    private CostForecastService service;

    @BeforeEach
    void setUp() {
        service = new CostForecastService(costDailySnapshotRepository);
    }

    // ---- pure regression math ----

    @Test
    void fitLinearRegression_recoversExactSlopeAndInterceptForNoiseFreeData() {
        // cost = 10*x + 100, exactly — OLS on a perfectly linear dataset must recover it exactly.
        double[] x = {0, 1, 2, 3, 4, 5};
        double[] y = {100, 110, 120, 130, 140, 150};

        CostForecastService.Regression regression = service.fitLinearRegression(x, y);

        assertEquals(10.0, regression.slope(), 0.0001);
        assertEquals(100.0, regression.intercept(), 0.0001);
    }

    @Test
    void fitLinearRegression_returnsFlatLineWhenAllXValuesIdentical() {
        double[] x = {5, 5, 5};
        double[] y = {10, 20, 30};

        CostForecastService.Regression regression = service.fitLinearRegression(x, y);

        assertEquals(0.0, regression.slope(), 0.0001, "zero-variance x must not divide by zero");
    }

    // ---- getForecast wiring ----

    @Test
    void getForecast_reportsInsufficientHistoryBelowMinimum() {
        List<CostDailySnapshotRepository.DailyCostTotal> totals = buildTotals(LocalDate.of(2026, 1, 1),
                new double[]{100, 110, 120}); // 3 days, well below MIN_HISTORY_DAYS
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(totals);

        CostForecastDTO result = service.getForecast(30, 14);

        assertFalse(result.sufficientHistory());
        assertEquals(3, result.historyDaysUsed());
        assertEquals(CostForecastService.MIN_HISTORY_DAYS, result.minHistoryDaysRequired());
        assertEquals(3, result.points().size(), "should return the raw history points even when insufficient, no forecast appended");
    }

    @Test
    void getForecast_projectsForwardAndFlagsForecastPointsCorrectly() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        double[] costs = new double[20];
        for (int i = 0; i < costs.length; i++) {
            costs[i] = 10 * i + 100; // perfectly linear: day 19 = 290
        }
        List<CostDailySnapshotRepository.DailyCostTotal> totals = buildTotals(start, costs);
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(totals);

        CostForecastDTO result = service.getForecast(30, 5);

        assertTrue(result.sufficientHistory());
        assertEquals(20, result.historyDaysUsed());
        assertEquals(25, result.points().size(), "20 history points + 5 forecast points");

        List<CostForecastPointDTO> forecastOnly = result.points().stream().filter(CostForecastPointDTO::isForecast).toList();
        assertEquals(5, forecastOnly.size());

        // Day 20 (1 day after the last historical day, index 19) should continue the exact trend: 10*20+100=300
        assertEquals(0, new BigDecimal("300.00").compareTo(forecastOnly.get(0).cost()));
        assertEquals(Date.valueOf(start.plusDays(20)), forecastOnly.get(0).date());

        assertTrue(result.points().stream().filter(p -> !p.isForecast()).allMatch(p -> !p.isForecast()));
    }

    @Test
    void getForecast_clampsNegativeProjectionToZero() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        double[] costs = new double[14];
        for (int i = 0; i < costs.length; i++) {
            costs[i] = 100 - (10 * i); // steep decline: day 13 = -30, already negative in history
        }
        List<CostDailySnapshotRepository.DailyCostTotal> totals = buildTotals(start, costs);
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(totals);

        CostForecastDTO result = service.getForecast(30, 3);

        List<CostForecastPointDTO> forecastOnly = result.points().stream().filter(CostForecastPointDTO::isForecast).toList();
        forecastOnly.forEach(p -> assertTrue(p.cost().compareTo(BigDecimal.ZERO) >= 0,
                "forecast cost must never be negative, got " + p.cost()));
    }

    // ---- helpers ----

    private List<CostDailySnapshotRepository.DailyCostTotal> buildTotals(LocalDate start, double[] estimatedCosts) {
        List<CostDailySnapshotRepository.DailyCostTotal> totals = new ArrayList<>();
        for (int i = 0; i < estimatedCosts.length; i++) {
            LocalDate date = start.plusDays(i);
            double cost = estimatedCosts[i];
            totals.add(new CostDailySnapshotRepository.DailyCostTotal() {
                @Override
                public Date getSnapshotDate() {
                    return Date.valueOf(date);
                }

                @Override
                public BigDecimal getTotalEstimatedCost() {
                    return BigDecimal.valueOf(cost);
                }

                @Override
                public BigDecimal getTotalActualCost() {
                    return null;
                }
            });
        }
        return totals;
    }
}
