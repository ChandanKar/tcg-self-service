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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- AWS forecast alongside (E35-T02) ----

    private com.tcgdigital.vmcontrol.model.AwsCostForecast aws(String granularity, LocalDate start, LocalDate end,
                                                               String mean, boolean total) {
        com.tcgdigital.vmcontrol.model.AwsCostForecast f = new com.tcgdigital.vmcontrol.model.AwsCostForecast();
        f.setGranularity(granularity);
        f.setPeriodStart(start);
        f.setPeriodEnd(end);
        f.setMean(new java.math.BigDecimal(mean));
        f.setLowerBound(new java.math.BigDecimal(mean).subtract(java.math.BigDecimal.TEN));
        f.setUpperBound(new java.math.BigDecimal(mean).add(java.math.BigDecimal.TEN));
        f.setTotal(total);
        return f;
    }

    @Test
    void awsDailyPointsWithinTheHorizonAreAttached() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        double[] costs = new double[20];
        java.util.Arrays.fill(costs, 100);
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(buildTotals(start, costs));
        LocalDate last = start.plusDays(19);
        com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository repo =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository.class);
        LocalDate generated = LocalDate.of(2026, 1, 19);
        when(repo.latestGeneration("DAILY", "MANAGED")).thenReturn(generated);
        when(repo.findByGeneratedOnAndGranularityAndScopeOrderByPeriodStart(generated, "DAILY", "MANAGED")).thenReturn(List.of(
                aws("DAILY", last, last.plusDays(1), "90", false),                 // the last history day: not attached
                aws("DAILY", last.plusDays(1), last.plusDays(2), "101", false),
                aws("DAILY", last.plusDays(14), last.plusDays(15), "114", false),
                aws("DAILY", last.plusDays(15), last.plusDays(16), "115", false), // past the 14-day horizon
                aws("DAILY", last.plusDays(1), last.plusDays(91), "9000", true)));  // the total: not a point
        service.setAwsForecasts(repo);

        CostForecastDTO result = service.getForecast(30, 14);

        assertEquals(2, result.awsDaily().size());
        assertEquals(0, new java.math.BigDecimal("101").compareTo(result.awsDaily().get(0).mean()));
        assertEquals(0, new java.math.BigDecimal("91").compareTo(result.awsDaily().get(0).lower()));
        assertEquals(java.sql.Date.valueOf(generated), result.awsGeneratedOn());
        assertTrue(result.sufficientHistory());
    }

    @Test
    void withoutAnAwsForecastTheListIsEmpty() {
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(buildTotals(LocalDate.of(2026, 1, 1), new double[]{1, 2, 3}));
        com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository repo =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository.class);
        service.setAwsForecasts(repo);

        CostForecastDTO result = service.getForecast(30, 14);

        assertTrue(result.awsDaily().isEmpty());
        assertNull(result.awsGeneratedOn());
    }

    @Test
    void theLongRangePairsAwsMonthsWithThePlatformRunRate() {
        when(costDailySnapshotRepository.findDailyTotalsSince(any())).thenReturn(buildTotals(LocalDate.of(2026, 1, 1), new double[]{10, 20, 30}));
        com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository repo =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository.class);
        LocalDate generated = LocalDate.of(2026, 10, 12);
        when(repo.latestGeneration("MONTHLY", "MANAGED")).thenReturn(generated);
        List<com.tcgdigital.vmcontrol.model.AwsCostForecast> months = new java.util.ArrayList<>();
        for (int i = 0; i < 18; i++) {
            LocalDate m = LocalDate.of(2026, 11, 1).plusMonths(i);
            months.add(aws("MONTHLY", m, m.plusMonths(1), "600", false));
        }
        months.add(aws("MONTHLY", LocalDate.of(2026, 11, 1), LocalDate.of(2028, 5, 1), "10800", true));
        when(repo.findByGeneratedOnAndGranularityAndScopeOrderByPeriodStart(generated, "MONTHLY", "MANAGED")).thenReturn(months);
        service.setAwsForecasts(repo);

        com.tcgdigital.vmcontrol.dto.LongRangeForecastDTO result = service.getLongRange();

        assertEquals(18, result.awsMonthly().size());
        assertEquals(18, result.platformRunRate().size());
        assertEquals(0, new java.math.BigDecimal("20.00").compareTo(result.averageDailyCost()));
        // November has 30 days: 20 x 30
        assertEquals(0, new java.math.BigDecimal("600.00").compareTo(result.platformRunRate().get(0).amount()));
        assertEquals(java.sql.Date.valueOf(generated), result.awsGeneratedOn());
    }
}
