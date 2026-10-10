package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.MetricValue;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostExplorerBillingServiceTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private CostDailySnapshotRepository costDailySnapshotRepository;
    @Mock private software.amazon.awssdk.services.costexplorer.CostExplorerClient ceClient;

    private CostExplorerBillingService service;

    // "Today" for the trailing window: 2026-10-07 (UTC).
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-07T05:00:00Z");

    @BeforeEach
    void setUp() {
        service = new CostExplorerBillingService(environmentRepository, costDailySnapshotRepository,
                new CostExplorerClientProvider(ceClient),
                new CostDayBoundary("UTC", java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "actualsEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tagKeyPrefix", "tcg:");
    }

    // ---- ingestWindow (E08-T02): one paginated request, per-day dates, overwrite ----

    private static ResultByTime day(String date, Group... groups) {
        return ResultByTime.builder()
                .timePeriod(software.amazon.awssdk.services.costexplorer.model.DateInterval.builder()
                        .start(date).end(LocalDate.parse(date).plusDays(1).toString()).build())
                .groups(groups)
                .build();
    }

    private Environment env(String name) {
        Environment e = new Environment();
        e.setEnvironmentId("id-" + name);
        e.setName(name);
        return e;
    }

    @Test
    void twoPagesAreMergedAndTheSecondRequestCarriesTheToken() {
        when(ceClient.getCostAndUsage(any(software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest.class)))
                .thenReturn(GetCostAndUsageResponse.builder()
                        .resultsByTime(day("2026-10-04", groupOf("tcg:environment$prod", "10")))
                        .nextPageToken("page-2").build())
                .thenReturn(GetCostAndUsageResponse.builder()
                        .resultsByTime(day("2026-10-05", groupOf("tcg:environment$prod", "20"))).build());
        when(environmentRepository.findByName("prod")).thenReturn(Optional.of(env("prod")));
        when(costDailySnapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any()))
                .thenAnswer(inv -> Optional.of(new CostDailySnapshot()));

        CostExplorerBillingService.IngestResult result = service.backfillActualCosts(30);

        ArgumentCaptor<software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest> requests =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest.class);
        org.mockito.Mockito.verify(ceClient, org.mockito.Mockito.times(2)).getCostAndUsage(requests.capture());
        assertEquals(null, requests.getAllValues().get(0).nextPageToken());
        assertEquals("page-2", requests.getAllValues().get(1).nextPageToken());
        // One request for the whole 30 days, ending today (exclusive).
        assertEquals("2026-09-07", requests.getAllValues().get(0).timePeriod().start());
        assertEquals("2026-10-07", requests.getAllValues().get(0).timePeriod().end());
        assertEquals(2, result.updated());
    }

    @Test
    void eachDaysCostLandsOnItsOwnDate() {
        Map<LocalDate, Map<String, BigDecimal>> byDate = service.parseCostByDateAndEnvironmentTag(List.of(
                day("2026-10-05", groupOf("tcg:environment$prod", "20")),
                day("2026-10-04", groupOf("tcg:environment$prod", "10"), groupOf("tcg:environment$qa", "1"))));

        assertEquals(0, new BigDecimal("10").compareTo(byDate.get(LocalDate.parse("2026-10-04")).get("prod")));
        assertEquals(0, new BigDecimal("1").compareTo(byDate.get(LocalDate.parse("2026-10-04")).get("qa")));
        assertEquals(0, new BigDecimal("20").compareTo(byDate.get(LocalDate.parse("2026-10-05")).get("prod")));
    }

    @Test
    void aProvisionalActualInsideTheWindowIsOverwritten() {
        when(ceClient.getCostAndUsage(any(software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest.class)))
                .thenReturn(GetCostAndUsageResponse.builder()
                        .resultsByTime(day("2026-10-06", groupOf("tcg:environment$prod", "95"))).build());
        when(environmentRepository.findByName("prod")).thenReturn(Optional.of(env("prod")));
        CostDailySnapshot yesterday = new CostDailySnapshot();
        yesterday.setActualCost(new BigDecimal("40"));
        when(costDailySnapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate("id-prod", Date.valueOf("2026-10-06")))
                .thenReturn(Optional.of(yesterday));

        service.ingestTrailingWindow(3);

        assertEquals(0, new BigDecimal("95").compareTo(yesterday.getActualCost()));
        ArgumentCaptor<software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest> request =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest.class);
        org.mockito.Mockito.verify(ceClient).getCostAndUsage(request.capture());
        assertEquals("2026-10-04", request.getValue().timePeriod().start());
        assertEquals("2026-10-07", request.getValue().timePeriod().end());
    }

    @Test
    void aDisabledOrUnconfiguredServiceMakesNoCalls() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "actualsEnabled", false);
        service.ingestTrailingWindow(3);

        CostExplorerBillingService unconfigured = new CostExplorerBillingService(environmentRepository,
                costDailySnapshotRepository, new CostExplorerClientProvider("", ""),
                new CostDayBoundary("UTC", java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)));
        org.springframework.test.util.ReflectionTestUtils.setField(unconfigured, "actualsEnabled", true);
        unconfigured.ingestTrailingWindow(3);

        org.mockito.Mockito.verifyNoInteractions(ceClient);
    }

    // ---- parseCostByEnvironmentTag ----

    @Test
    void parseCostByEnvironmentTag_sumsAmountsPerTagValue() {
        GetCostAndUsageResponse response = GetCostAndUsageResponse.builder()
                .resultsByTime(ResultByTime.builder()
                        .groups(
                                groupOf("tcg:environment$prod-01", "12.50"),
                                groupOf("tcg:environment$prod-02", "3.25"))
                        .build())
                .build();

        Map<String, BigDecimal> result = service.parseCostByEnvironmentTag(response);

        assertEquals(0, new BigDecimal("12.50").compareTo(result.get("prod-01")));
        assertEquals(0, new BigDecimal("3.25").compareTo(result.get("prod-02")));
        assertEquals(2, result.size());
    }

    @Test
    void parseCostByEnvironmentTag_sumsAcrossMultipleResultsByTimeForSameTagValue() {
        GetCostAndUsageResponse response = GetCostAndUsageResponse.builder()
                .resultsByTime(
                        ResultByTime.builder().groups(groupOf("tcg:environment$prod-01", "5.00")).build(),
                        ResultByTime.builder().groups(groupOf("tcg:environment$prod-01", "7.50")).build())
                .build();

        Map<String, BigDecimal> result = service.parseCostByEnvironmentTag(response);

        assertEquals(0, new BigDecimal("12.50").compareTo(result.get("prod-01")));
    }

    @Test
    void parseCostByEnvironmentTag_skipsUntaggedBucket() {
        GetCostAndUsageResponse response = GetCostAndUsageResponse.builder()
                .resultsByTime(ResultByTime.builder()
                        .groups(groupOf("tcg:environment$", "99.00"))
                        .build())
                .build();

        Map<String, BigDecimal> result = service.parseCostByEnvironmentTag(response);

        assertFalse(result.containsKey(""));
        assertEquals(0, result.size());
    }

    @Test
    void parseCostByEnvironmentTag_skipsGroupWithNoKeys() {
        GetCostAndUsageResponse response = GetCostAndUsageResponse.builder()
                .resultsByTime(ResultByTime.builder()
                        .groups(Group.builder().keys(List.of()).metrics(Map.of()).build())
                        .build())
                .build();

        Map<String, BigDecimal> result = service.parseCostByEnvironmentTag(response);

        assertEquals(0, result.size());
    }

    private Group groupOf(String key, String amount) {
        return Group.builder()
                .keys(key)
                .metrics(Map.of("UnblendedCost", MetricValue.builder().amount(amount).unit("USD").build()))
                .build();
    }

    // ---- applyActualCosts ----

    @Test
    void applyActualCosts_updatesMatchingSnapshotRow() {
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("prod-01");
        when(environmentRepository.findByName("prod-01")).thenReturn(Optional.of(env));

        CostDailySnapshot snapshot = new CostDailySnapshot();
        snapshot.setEstimatedCost(BigDecimal.TEN);
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(costDailySnapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate("env-1", Date.valueOf(date)))
                .thenReturn(Optional.of(snapshot));

        CostExplorerBillingService.IngestResult result =
                service.applyActualCosts(date, Map.of("prod-01", new BigDecimal("42.00")));

        assertEquals(1, result.updated());
        assertEquals(0, result.skippedNoEnvironment());
        assertEquals(0, result.skippedNoSnapshotRow());

        ArgumentCaptor<CostDailySnapshot> captor = ArgumentCaptor.forClass(CostDailySnapshot.class);
        org.mockito.Mockito.verify(costDailySnapshotRepository).save(captor.capture());
        assertEquals(0, new BigDecimal("42.00").compareTo(captor.getValue().getActualCost()));
    }

    @Test
    void applyActualCosts_skipsWhenNoMatchingEnvironment() {
        when(environmentRepository.findByName("unknown-env")).thenReturn(Optional.empty());

        CostExplorerBillingService.IngestResult result =
                service.applyActualCosts(LocalDate.now(), Map.of("unknown-env", BigDecimal.ONE));

        assertEquals(0, result.updated());
        assertEquals(1, result.skippedNoEnvironment());
        assertEquals(0, result.skippedNoSnapshotRow());
    }

    @Test
    void applyActualCosts_skipsWhenNoExistingSnapshotRow() {
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("prod-01");
        when(environmentRepository.findByName("prod-01")).thenReturn(Optional.of(env));
        when(costDailySnapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any()))
                .thenReturn(Optional.empty());

        CostExplorerBillingService.IngestResult result =
                service.applyActualCosts(LocalDate.now(), Map.of("prod-01", BigDecimal.ONE));

        assertEquals(0, result.updated());
        assertEquals(0, result.skippedNoEnvironment());
        assertEquals(1, result.skippedNoSnapshotRow());
    }
}
