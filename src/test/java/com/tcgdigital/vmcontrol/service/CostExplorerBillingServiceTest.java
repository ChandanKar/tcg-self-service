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

    private CostExplorerBillingService service;

    @BeforeEach
    void setUp() {
        service = new CostExplorerBillingService(environmentRepository, costDailySnapshotRepository);
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
