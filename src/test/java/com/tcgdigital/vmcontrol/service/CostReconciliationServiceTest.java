package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostReconciliationRowDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostReconciliationServiceTest {

    @Mock private CostDailySnapshotRepository costDailySnapshotRepository;

    private CostReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new CostReconciliationService(costDailySnapshotRepository);
    }

    @Test
    void getReconciliation_computesVariancePercentWhenActualCostKnown() {
        Environment env = buildEnvironment("env-1", "prod-01");
        List<CostDailySnapshot> snapshots = List.of(
                buildSnapshot(env, "100.00", "80.00"),
                buildSnapshot(env, "50.00", "40.00")
        );
        when(costDailySnapshotRepository.findWithEnvironmentSince(any())).thenReturn(snapshots);

        List<CostReconciliationRowDTO> result = service.getReconciliation(30);

        assertEquals(1, result.size());
        CostReconciliationRowDTO row = result.get(0);
        assertEquals("prod-01", row.environmentName());
        assertEquals(0, new BigDecimal("150.00").compareTo(row.estimatedCost()));
        assertEquals(0, new BigDecimal("120.00").compareTo(row.actualCost()));
        // (150 - 120) / 120 * 100 = 25.0%
        assertEquals(0, new BigDecimal("25.0").compareTo(row.variancePercent()));
    }

    @Test
    void getReconciliation_leavesActualCostAndVarianceNullWhenNeverIngested() {
        Environment env = buildEnvironment("env-1", "prod-01");
        List<CostDailySnapshot> snapshots = List.of(buildSnapshot(env, "100.00", null));
        when(costDailySnapshotRepository.findWithEnvironmentSince(any())).thenReturn(snapshots);

        List<CostReconciliationRowDTO> result = service.getReconciliation(30);

        assertEquals(1, result.size());
        assertNull(result.get(0).actualCost());
        assertNull(result.get(0).variancePercent());
    }

    @Test
    void getReconciliation_sortsByEstimatedCostDescending() {
        Environment envA = buildEnvironment("env-a", "small-env");
        Environment envB = buildEnvironment("env-b", "big-env");
        List<CostDailySnapshot> snapshots = List.of(
                buildSnapshot(envA, "10.00", null),
                buildSnapshot(envB, "500.00", null)
        );
        when(costDailySnapshotRepository.findWithEnvironmentSince(any())).thenReturn(snapshots);

        List<CostReconciliationRowDTO> result = service.getReconciliation(30);

        assertEquals("big-env", result.get(0).environmentName());
        assertEquals("small-env", result.get(1).environmentName());
    }

    // ---- Like-for-like variance and coverage (E08-T03, H18) ----

    @Test
    void varianceComparesOnlyTheDaysThatHaveActualsAndReportsCoverage() {
        Environment env = buildEnvironment("env-1", "prod-01");
        java.util.List<CostDailySnapshot> snapshots = new java.util.ArrayList<>();
        LocalDate first = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 90; i++) {
            CostDailySnapshot s = buildSnapshot(env, "100.00", i >= 80 ? "100.00" : null);
            s.setSnapshotDate(Date.valueOf(first.plusDays(i)));
            snapshots.add(s);
        }
        when(costDailySnapshotRepository.findWithEnvironmentSince(any())).thenReturn(snapshots);

        CostReconciliationRowDTO row = service.getReconciliation(90).get(0);

        assertEquals(0, new BigDecimal("9000.00").compareTo(row.estimatedCost()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(row.estimatedCostOnActualDays()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(row.actualCost()));
        assertEquals(0, new BigDecimal("0.0").compareTo(row.variancePercent()));  // not +800%
        assertEquals(10, row.daysWithActuals());
        assertEquals(90, row.daysInWindow());
    }

    @Test
    void withoutActualsCoverageIsZero() {
        Environment env = buildEnvironment("env-1", "prod-01");
        when(costDailySnapshotRepository.findWithEnvironmentSince(any())).thenReturn(List.of(buildSnapshot(env, "100.00", null)));

        CostReconciliationRowDTO row = service.getReconciliation(30).get(0);

        assertEquals(0, row.daysWithActuals());
        assertEquals(1, row.daysInWindow());
        assertNull(row.variancePercent());
    }

    private Environment buildEnvironment(String id, String displayName) {
        Environment env = new Environment();
        env.setEnvironmentId(id);
        env.setDisplayName(displayName);
        return env;
    }

    private CostDailySnapshot buildSnapshot(Environment env, String estimatedCost, String actualCost) {
        CostDailySnapshot snapshot = new CostDailySnapshot();
        snapshot.setEnvironment(env);
        snapshot.setSnapshotDate(Date.valueOf("2026-01-15"));
        snapshot.setEstimatedCost(new BigDecimal(estimatedCost));
        if (actualCost != null) {
            snapshot.setActualCost(new BigDecimal(actualCost));
        }
        return snapshot;
    }
}
