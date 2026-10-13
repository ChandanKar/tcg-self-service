package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AwsCostForecast;
import com.tcgdigital.vmcontrol.repository.AwsCostForecastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Capturing AWS's cost forecast (E35-T01): the requests, the parsing, and too little history. */
@ExtendWith(MockitoExtension.class)
class AwsCostForecastServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Mock private CostExplorerClient client;
    @Mock private AwsCostForecastRepository repository;
    @Mock private CostDayBoundary dayBoundary;

    private AwsCostForecastService service;
    private final List<AwsCostForecast> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AwsCostForecastService(new CostExplorerClientProvider(client), repository, dayBoundary);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "predictionInterval", 80);
        ReflectionTestUtils.setField(service, "tagKeyPrefix", "tcg:");
        lenient().when(dayBoundary.today()).thenReturn(TODAY);
        lenient().when(repository.save(any(AwsCostForecast.class))).thenAnswer(inv -> { saved.add(inv.getArgument(0)); return inv.getArgument(0); });
    }

    private static GetCostForecastResponse monthly(int points) {
        List<ForecastResult> results = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 11, 1);
        for (int i = 0; i < points; i++) {
            results.add(ForecastResult.builder()
                    .timePeriod(DateInterval.builder().start(start.plusMonths(i).toString()).end(start.plusMonths(i + 1).toString()).build())
                    .meanValue("1000.456").predictionIntervalLowerBound("900.1").predictionIntervalUpperBound("1100.9").build());
        }
        return GetCostForecastResponse.builder().forecastResultsByTime(results)
                .total(MetricValue.builder().amount("18008.21").unit("USD").build()).build();
    }

    private static GetCostForecastResponse daily() {
        return GetCostForecastResponse.builder().forecastResultsByTime(ForecastResult.builder()
                .timePeriod(DateInterval.builder().start("2026-10-15").end("2026-10-16").build())
                .meanValue("33.3").predictionIntervalLowerBound("30").predictionIntervalUpperBound("36").build()).build();
    }

    @Test
    void bothForecastsAskForThePlatformsResourcesWithTheirPeriodsAndInterval() {
        when(client.getCostForecast(any(GetCostForecastRequest.class))).thenReturn(daily(), monthly(18));

        service.capture();

        ArgumentCaptor<GetCostForecastRequest> requests = ArgumentCaptor.forClass(GetCostForecastRequest.class);
        verify(client, times(2)).getCostForecast(requests.capture());
        GetCostForecastRequest dailyRequest = requests.getAllValues().get(0);
        GetCostForecastRequest monthlyRequest = requests.getAllValues().get(1);
        assertThat(dailyRequest.granularity()).isEqualTo(Granularity.DAILY);
        assertThat(dailyRequest.timePeriod().start()).isEqualTo("2026-10-15");
        assertThat(dailyRequest.timePeriod().end()).isEqualTo("2027-01-13");
        assertThat(monthlyRequest.granularity()).isEqualTo(Granularity.MONTHLY);
        assertThat(monthlyRequest.timePeriod().start()).isEqualTo("2026-11-01");
        assertThat(monthlyRequest.timePeriod().end()).isEqualTo("2028-05-01");
        for (GetCostForecastRequest r : requests.getAllValues()) {
            assertThat(r.metric()).isEqualTo(Metric.UNBLENDED_COST);
            assertThat(r.predictionIntervalLevel()).isEqualTo(80);
            assertThat(r.filter().tags().key()).isEqualTo("tcg:managed-by");
            assertThat(r.filter().tags().values()).containsExactly("vmcontrol");
        }
    }

    @Test
    void eighteenMonthlyPointsWithMeanAndBoundsAreStoredWithTheTotal() {
        when(client.getCostForecast(any(GetCostForecastRequest.class))).thenReturn(daily(), monthly(18));

        assertThat(service.capture()).isEqualTo(19);

        List<AwsCostForecast> months = saved.stream().filter(f -> f.getGranularity().equals(AwsCostForecast.MONTHLY) && !f.isTotal()).toList();
        assertThat(months).hasSize(18);
        assertThat(months.get(0).getMean()).isEqualByComparingTo("1000.46");
        assertThat(months.get(0).getLowerBound()).isEqualByComparingTo("900.10");
        assertThat(months.get(0).getUpperBound()).isEqualByComparingTo("1100.90");
        assertThat(months.get(0).getGeneratedOn()).isEqualTo(TODAY);
        assertThat(saved).filteredOn(AwsCostForecast::isTotal).singleElement()
                .satisfies(t -> assertThat(t.getMean()).isEqualByComparingTo("18008.21"));
        verify(repository).deleteGeneration(TODAY, AwsCostForecast.MONTHLY, AwsCostForecast.MANAGED);
    }

    @Test
    void tooLittleHistoryStoresNothing() {
        when(client.getCostForecast(any(GetCostForecastRequest.class)))
                .thenThrow(DataUnavailableException.builder().message("Insufficient amount of historical data").build());

        assertThat(service.capture()).isZero();

        verify(repository, never()).save(any());
        verify(repository, never()).deleteGeneration(any(), any(), any());
    }

    @Test
    void withoutCredentialsOrSwitchedOffNothingIsCalled() {
        AwsCostForecastService unconfigured = new AwsCostForecastService(CostExplorerClientProvider.unconfigured(), repository, dayBoundary);
        ReflectionTestUtils.setField(unconfigured, "enabled", true);
        assertThat(unconfigured.capture()).isZero();

        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.capture()).isZero();

        verifyNoInteractions(client);
    }
}
