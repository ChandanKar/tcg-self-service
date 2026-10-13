package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AwsCostForecast;
import com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.DataUnavailableException;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.ForecastResult;
import software.amazon.awssdk.services.costexplorer.model.GetCostForecastRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostForecastResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Metric;
import software.amazon.awssdk.services.costexplorer.model.TagValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * AWS Cost Explorer's forecast for the resources the platform manages (E35-T01): GetCostForecast,
 * unblended cost, an 80% prediction interval, filtered to the {@code <prefix>managed-by} tag that
 * tag reconciliation writes. Daily for the next 90 days and monthly for the next 18 months, two
 * billed calls a week. Too little history (DataUnavailableException) stores nothing.
 */
@Service
public class AwsCostForecastService {

    private static final Logger log = LoggerFactory.getLogger(AwsCostForecastService.class);
    static final int DAILY_DAYS = 90;
    static final int MONTHLY_MONTHS = 18;

    @Value("${cost.aws-forecast.enabled:true}")
    private boolean enabled;

    @Value("${cost.aws-forecast.prediction-interval:80}")
    private int predictionInterval;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix;

    @Autowired(required = false)
    private SettingsService settings;

    private final CostExplorerClientProvider clientProvider;
    private final AwsCostForecastRepository repository;
    private final CostDayBoundary dayBoundary;

    public AwsCostForecastService(CostExplorerClientProvider clientProvider, AwsCostForecastRepository repository,
                                  CostDayBoundary dayBoundary) {
        this.clientProvider = clientProvider;
        this.repository = repository;
        this.dayBoundary = dayBoundary;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("cost.aws-forecast.enabled", enabled);
    }

    /** Captures both forecasts; returns how many points were stored. */
    public int capture() {
        if (!isEnabled() || !clientProvider.isConfigured()) {
            return 0;
        }
        LocalDate today = dayBoundary.today();
        LocalDate dailyStart = today.plusDays(1);
        LocalDate monthlyStart = today.withDayOfMonth(1).plusMonths(1);
        return capture(today, AwsCostForecast.DAILY, Granularity.DAILY, dailyStart, dailyStart.plusDays(DAILY_DAYS))
                + capture(today, AwsCostForecast.MONTHLY, Granularity.MONTHLY, monthlyStart, monthlyStart.plusMonths(MONTHLY_MONTHS));
    }

    private int capture(LocalDate today, String granularity, Granularity awsGranularity, LocalDate start, LocalDate end) {
        GetCostForecastResponse response;
        try {
            response = clientProvider.client().getCostForecast(request(awsGranularity, start, end));
        } catch (DataUnavailableException e) {
            log.warn("AWS has too little cost history for a {} forecast yet; nothing stored", granularity);
            return 0;
        } catch (Exception e) {
            log.error("AWS {} cost forecast failed: {}", granularity, e.getMessage());
            return 0;
        }
        repository.deleteGeneration(today, granularity, AwsCostForecast.MANAGED);
        int stored = 0;
        for (ForecastResult r : response.forecastResultsByTime()) {
            repository.save(point(today, granularity, LocalDate.parse(r.timePeriod().start()), LocalDate.parse(r.timePeriod().end()),
                    r.meanValue(), r.predictionIntervalLowerBound(), r.predictionIntervalUpperBound(), false));
            stored++;
        }
        if (response.total() != null && response.total().amount() != null) {
            repository.save(point(today, granularity, start, end, response.total().amount(), null, null, true));
        }
        log.info("Stored {} {} AWS forecast point(s) for {}..{}", stored, granularity, start, end);
        return stored;
    }

    /** The request: unblended cost, the prediction interval, platform-managed resources only. */
    GetCostForecastRequest request(Granularity granularity, LocalDate start, LocalDate end) {
        return GetCostForecastRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .metric(Metric.UNBLENDED_COST)
                .granularity(granularity)
                .predictionIntervalLevel(predictionInterval)
                .filter(Expression.builder().tags(TagValues.builder()
                        .key(tagKeyPrefix + "managed-by")
                        .values(TagReconciliationService.MANAGED_BY_VALUE)
                        .build()).build())
                .build();
    }

    private static AwsCostForecast point(LocalDate generatedOn, String granularity, LocalDate start, LocalDate end,
                                         String mean, String lower, String upper, boolean total) {
        AwsCostForecast f = new AwsCostForecast();
        f.setForecastId(UUID.randomUUID().toString());
        f.setGeneratedOn(generatedOn);
        f.setGranularity(granularity);
        f.setPeriodStart(start);
        f.setPeriodEnd(end);
        f.setMean(money(mean));
        f.setLowerBound(money(lower));
        f.setUpperBound(money(upper));
        f.setTotal(total);
        return f;
    }

    private static BigDecimal money(String value) {
        if (value == null || value.isBlank()) return null;
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }
}
