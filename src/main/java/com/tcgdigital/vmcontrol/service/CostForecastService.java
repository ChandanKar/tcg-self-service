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

    /** AWS's captured forecast (E35-T02); absent in plain unit tests. */
    private com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository awsForecasts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAwsForecasts(com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository awsForecasts) {
        this.awsForecasts = awsForecasts;
    }

    /** AWS daily points and when they were captured. */
    private record AwsDaily(List<com.tcgdigital.vmcontrol.dto.AwsForecastPointDTO> points, Date generatedOn) {
        static final AwsDaily NONE = new AwsDaily(List.of(), null);
    }

    /** The latest AWS daily forecast for the days after {@code afterDate}, up to {@code days} of them. */
    private AwsDaily awsDaily(LocalDate afterDate, int days) {
        if (awsForecasts == null) return AwsDaily.NONE;
        LocalDate generated = awsForecasts.latestGeneration(com.tcgdigital.vmcontrol.model.AwsCostForecast.DAILY,
                com.tcgdigital.vmcontrol.model.AwsCostForecast.MANAGED);
        if (generated == null) return AwsDaily.NONE;
        LocalDate last = afterDate.plusDays(days);
        List<com.tcgdigital.vmcontrol.dto.AwsForecastPointDTO> points = awsForecasts
                .findByGeneratedOnAndGranularityAndScopeOrderByPeriodStart(generated,
                        com.tcgdigital.vmcontrol.model.AwsCostForecast.DAILY, com.tcgdigital.vmcontrol.model.AwsCostForecast.MANAGED)
                .stream()
                .filter(f -> !f.isTotal() && f.getPeriodStart().isAfter(afterDate) && !f.getPeriodStart().isAfter(last))
                .map(CostForecastService::point)
                .toList();
        return new AwsDaily(points, Date.valueOf(generated));
    }

    private static com.tcgdigital.vmcontrol.dto.AwsForecastPointDTO point(com.tcgdigital.vmcontrol.model.AwsCostForecast f) {
        return new com.tcgdigital.vmcontrol.dto.AwsForecastPointDTO(Date.valueOf(f.getPeriodStart()), Date.valueOf(f.getPeriodEnd()),
                f.getMean(), f.getLowerBound(), f.getUpperBound());
    }

    private static CostForecastDTO withAws(CostForecastDTO base, AwsDaily aws) {
        return aws.points().isEmpty() ? base : new CostForecastDTO(base.points(), base.sufficientHistory(), base.historyDaysUsed(),
                base.minHistoryDaysRequired(), aws.points(), aws.generatedOn());
    }

    /**
     * The 18-month view (E35-T02): AWS's latest monthly forecast and the platform's run-rate for
     * the same months (the average daily estimated cost of the last 30 days times each month's days).
     */
    public com.tcgdigital.vmcontrol.dto.LongRangeForecastDTO getLongRange() {
        List<CostDailySnapshotRepository.DailyCostTotal> totals =
                costDailySnapshotRepository.findDailyTotalsSince(Date.valueOf(LocalDate.now().minusDays(30)));
        BigDecimal average = totals.isEmpty() ? null : totals.stream().map(t -> nullToZero(t.getTotalEstimatedCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(totals.size()), 2, RoundingMode.HALF_UP);
        LocalDate generated = awsForecasts == null ? null : awsForecasts.latestGeneration(
                com.tcgdigital.vmcontrol.model.AwsCostForecast.MONTHLY, com.tcgdigital.vmcontrol.model.AwsCostForecast.MANAGED);
        List<com.tcgdigital.vmcontrol.dto.AwsForecastPointDTO> monthly = generated == null ? List.of()
                : awsForecasts.findByGeneratedOnAndGranularityAndScopeOrderByPeriodStart(generated,
                        com.tcgdigital.vmcontrol.model.AwsCostForecast.MONTHLY, com.tcgdigital.vmcontrol.model.AwsCostForecast.MANAGED)
                .stream().filter(f -> !f.isTotal()).map(CostForecastService::point).toList();
        List<com.tcgdigital.vmcontrol.dto.LongRangeForecastDTO.RunRate> runRate = new ArrayList<>();
        if (average != null) {
            List<LocalDate> months = new ArrayList<>();
            if (!monthly.isEmpty()) {
                monthly.forEach(p -> months.add(p.periodStart().toLocalDate()));
            } else {
                LocalDate first = LocalDate.now().withDayOfMonth(1).plusMonths(1);
                for (int i = 0; i < 18; i++) months.add(first.plusMonths(i));
            }
            for (LocalDate month : months) {
                runRate.add(new com.tcgdigital.vmcontrol.dto.LongRangeForecastDTO.RunRate(Date.valueOf(month),
                        average.multiply(BigDecimal.valueOf(month.lengthOfMonth())).setScale(2, RoundingMode.HALF_UP)));
            }
        }
        return new com.tcgdigital.vmcontrol.dto.LongRangeForecastDTO(generated == null ? null : Date.valueOf(generated),
                monthly, runRate, average);
    }

    public CostForecastDTO getForecast(int historyDays, int forecastDays) {
        Date since = Date.valueOf(LocalDate.now().minusDays(historyDays));
        List<CostDailySnapshotRepository.DailyCostTotal> totals = costDailySnapshotRepository.findDailyTotalsSince(since);

        List<CostForecastPointDTO> historyPoints = new ArrayList<>();
        for (CostDailySnapshotRepository.DailyCostTotal t : totals) {
            historyPoints.add(new CostForecastPointDTO(t.getSnapshotDate(), nullToZero(t.getTotalEstimatedCost()), false));
        }

        // AWS's forecast for the same horizon, from the day after the last history point (E35-T02).
        LocalDate lastHistory = historyPoints.isEmpty() ? LocalDate.now().minusDays(1)
                : historyPoints.get(historyPoints.size() - 1).date().toLocalDate();
        var aws = awsDaily(lastHistory, forecastDays);

        if (historyPoints.size() < MIN_HISTORY_DAYS) {
            return withAws(new CostForecastDTO(historyPoints, false, historyPoints.size(), MIN_HISTORY_DAYS), aws);
        }

        List<CostForecastPointDTO> forecastPoints = projectForward(historyPoints, forecastDays);

        List<CostForecastPointDTO> allPoints = new ArrayList<>(historyPoints);
        allPoints.addAll(forecastPoints);
        return withAws(new CostForecastDTO(allPoints, true, historyPoints.size(), MIN_HISTORY_DAYS), aws);
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
