package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.EfficiencySnapshot;
import com.tcgdigital.vmcontrol.repository.EfficiencySnapshotRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Daily efficiency snapshots (E17-T05): one fleet row per day, re-runs replace it, trends read back. */
class EfficiencySnapshotIntegrationTest extends AbstractIntegrationTest {

    @Autowired private PotentialSavingsService potentialSavingsService;
    @Autowired private EfficiencySnapshotRepository snapshots;
    @Autowired private CostDayBoundary dayBoundary;

    @Test
    void capturingTodayTwiceKeepsOneFleetRow() {
        newEnvironment("Efficiency");
        potentialSavingsService.captureSnapshot();
        potentialSavingsService.captureSnapshot();
        LocalDate today = dayBoundary.today();
        assertThat(snapshots.findByEnvironmentIdIsNullAndSnapshotDateBetweenOrderBySnapshotDateAsc(today, today)).hasSize(1);
    }

    @Test
    void thirtyDailyRunsGiveThirtyFleetPoints() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 30; i++) {
            EfficiencySnapshot s = new EfficiencySnapshot();
            s.setSnapshotId(UUID.randomUUID().toString());
            s.setSnapshotDate(start.plusDays(i));
            s.setPotentialSavings(new BigDecimal("170"));
            s.setOptimisableSpend(new BigDecimal("1000"));
            s.setEfficiencyPercent(new BigDecimal("83.0"));
            snapshots.save(s);
        }
        snapshots.flush();
        assertThat(snapshots.findByEnvironmentIdIsNullAndSnapshotDateBetweenOrderBySnapshotDateAsc(start, start.plusDays(29)))
                .hasSize(30);
    }
}
