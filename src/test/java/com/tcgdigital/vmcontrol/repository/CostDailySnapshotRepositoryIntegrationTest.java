package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-environment weekly totals carry actuals coverage (E08-T03): the estimate on days with
 * actuals and the number of such days. Against MySQL.
 */
class CostDailySnapshotRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CostDailySnapshotRepository repository;

    private void snapshot(Environment env, LocalDate day, String estimated, String actual) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(day));
        s.setEstimatedCost(new BigDecimal(estimated));
        s.setActualCost(actual == null ? null : new BigDecimal(actual));
        repository.saveAndFlush(s);
    }

    @Test
    void weeklyTotalsCountTheDaysWithActualsAndTheirEstimate() {
        Environment env = newEnvironment("CostTotals");
        LocalDate start = LocalDate.of(2031, 3, 2); // far from any other test's data
        for (int i = 0; i < 7; i++) {
            snapshot(env, start.plusDays(i), "10.00", i < 3 ? "11.00" : null);
        }

        CostDailySnapshotRepository.EnvironmentCostTotal total = repository
                .sumByEnvironmentBetween(Date.valueOf(start), Date.valueOf(start.plusDays(7))).stream()
                .filter(t -> t.getEnvironmentId().equals(env.getEnvironmentId()))
                .findFirst().orElseThrow();

        assertThat(total.getTotalEstimatedCost()).isEqualByComparingTo("70.00");
        assertThat(total.getTotalActualCost()).isEqualByComparingTo("33.00");
        assertThat(total.getEstimatedOnActualDays()).isEqualByComparingTo("30.00");
        assertThat(total.getActualDays()).isEqualTo(3L);
    }
}
