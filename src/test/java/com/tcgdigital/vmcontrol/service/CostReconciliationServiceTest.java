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
