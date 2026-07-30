package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostForecastDTO;
import com.tcgdigital.vmcontrol.dto.CostForecastPointDTO;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Cost forecast over the existing daily cost-snapshot history — no AWS dependency, works off
 * data v1 already accumulates via {@code CostSnapshotScheduler}. Deliberately the simplest model
 * that could work: ordinary least squares over (day-offset, total estimated cost) pairs, not a
 * seasonal/ARIMA model — transparent and auditable, matching this feature's existing "never
 * fabricate a number" discipline elsewhere (idle waste, rightsizing, reconciliation). Refuses to
 * forecast below a minimum history length rather than extrapolate a trend line from noise, and
 * clamps any projected cost at zero (a steep declining trend can otherwise extrapolate negative).
 */
@Service
public class CostForecastService {

    static final int MIN_HISTORY_DAYS = 14;

    private final CostDailySnapshotRepository costDailySnapshotRepository;

    public CostForecastService(CostDailySnapshotRepository costDailySnapshotRepository) {
        this.costDailySnapshotRepository = costDailySnapshotRepository;
    }

    public CostForecastDTO getForecast(int historyDays, int forecastDays) {
        Date since = Date.valueOf(LocalDate.now().minusDays(historyDays));
        List<CostDailySnapshotRepository.DailyCostTotal> totals = costDailySnapshotRepository.findDailyTotalsSince(since);

        List<CostForecastPointDTO> historyPoints = new ArrayList<>();
        for (CostDailySnapshotRepository.DailyCostTotal t : totals) {
            historyPoints.add(new CostForecastPointDTO(t.getSnapshotDate(), nullToZero(t.getTotalEstimatedCost()), false));
        }

        if (historyPoints.size() < MIN_HISTORY_DAYS) {
            return new CostForecastDTO(historyPoints, false, historyPoints.size(), MIN_HISTORY_DAYS);
        }

        List<CostForecastPointDTO> forecastPoints = projectForward(historyPoints, forecastDays);

        List<CostForecastPointDTO> allPoints = new ArrayList<>(historyPoints);
        allPoints.addAll(forecastPoints);
        return new CostForecastDTO(allPoints, true, historyPoints.size(), MIN_HISTORY_DAYS);
    }

    /**
     * Fits an OLS line to the (day-offset-from-first-point, cost) history and projects it forward
     * {@code forecastDays} calendar days past the last historical date. Day-offset is used
     * instead of a plain list index so a gap in the snapshot history (a day the scheduler missed)
     * doesn't compress the time axis and skew the slope.
     */
    private List<CostForecastPointDTO> projectForward(List<CostForecastPointDTO> historyPoints, int forecastDays) {
        LocalDate firstDate = historyPoints.get(0).date().toLocalDate();
        double[] x = new double[historyPoints.size()];
        double[] y = new double[historyPoints.size()];
        for (int i = 0; i < historyPoints.size(); i++) {
            x[i] = ChronoUnit.DAYS.between(firstDate, historyPoints.get(i).date().toLocalDate());
            y[i] = historyPoints.get(i).cost().doubleValue();
        }

        Regression regression = fitLinearRegression(x, y);
        LocalDate lastDate = historyPoints.get(historyPoints.size() - 1).date().toLocalDate();

        List<CostForecastPointDTO> forecastPoints = new ArrayList<>();
        for (int i = 1; i <= forecastDays; i++) {
            LocalDate forecastDate = lastDate.plusDays(i);
            double xValue = ChronoUnit.DAYS.between(firstDate, forecastDate);
            double predicted = regression.slope() * xValue + regression.intercept();
            BigDecimal cost = BigDecimal.valueOf(Math.max(0, predicted)).setScale(2, RoundingMode.HALF_UP);
            forecastPoints.add(new CostForecastPointDTO(Date.valueOf(forecastDate), cost, true));
        }
        return forecastPoints;
    }

    /**
     * Ordinary least squares — package-private and pure (no DB access) so it's directly
     * unit-testable against synthetic data.
     */
    Regression fitLinearRegression(double[] x, double[] y) {
        int n = x.length;
        double meanX = mean(x);
        double meanY = mean(y);

        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (x[i] - meanX) * (y[i] - meanY);
            denominator += (x[i] - meanX) * (x[i] - meanX);
        }

        double slope = denominator == 0 ? 0 : numerator / denominator;
        double intercept = meanY - slope * meanX;
        return new Regression(slope, intercept);
    }

    private double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    record Regression(double slope, double intercept) {
    }
}
