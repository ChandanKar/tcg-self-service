package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.CostEstimationService.VmOpportunity;
import com.tcgdigital.vmcontrol.service.PotentialSavingsService.PotentialCause;
import com.tcgdigital.vmcontrol.service.PotentialSavingsService.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** De-duplicated potential savings and Cost Efficiency % (E17-T05). */
class PotentialSavingsServiceTest {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private static VmOpportunity vm(String id, String env, String cost, boolean idle, String idleCost, String rightsizing,
                                    boolean production, boolean lease) {
        return new VmOpportunity(id, env, "g-" + id, "Team", new BigDecimal(cost), true, idle,
                new BigDecimal(idleCost), new BigDecimal(rightsizing), production, lease);
    }

    @Test
    void anIdleVmCountsItsLargestOpportunityOnce() {
        // Scheduled (no off-hours potential): idle $50 vs rightsizing $20 -> $50, not $70.
        Result r = PotentialSavingsService.summarize(List.of(vm("a", "e1", "100", true, "50", "20", false, false)),
                Set.of("ENVIRONMENT:e1"), HALF);
        assertThat(r.potentialSavings()).isEqualByComparingTo("50");
        assertThat(r.byCause()).containsOnlyKeys(PotentialCause.IDLE);
    }

    @Test
    void efficiencyIsOneMinusPotentialOverOptimisable() {
        // $1,000 optimisable, $170 potential -> 83.0 %.
        Result r = PotentialSavingsService.summarize(List.of(
                vm("a", "e1", "600", true, "120", "0", false, false),
                vm("b", "e1", "400", false, "0", "50", false, false)), Set.of("ENVIRONMENT:e1"), HALF);
        assertThat(r.optimisableSpend()).isEqualByComparingTo("1000");
        assertThat(r.potentialSavings()).isEqualByComparingTo("170");
        assertThat(r.efficiencyPercent()).isEqualByComparingTo("83.0");
    }

    @Test
    void unscheduledNonProductionVmsCountTheOffHoursShare() {
        Result r = PotentialSavingsService.summarize(List.of(
                vm("free", "e1", "100", false, "0", "10", false, false),   // 50 off-hours beats 10
                vm("prod", "e2", "100", false, "0", "10", true, false),    // production: rightsizing only
                vm("leased", "e3", "100", false, "0", "0", false, true),   // a lease stops it
                vm("vmrule", "e4", "100", false, "0", "0", false, false)), // a VM-scoped schedule
                Set.of("VM:vmrule"), HALF);
        assertThat(r.byCause().get(PotentialCause.OFF_HOURS)).isEqualByComparingTo("50");
        assertThat(r.byCause().get(PotentialCause.RIGHTSIZING)).isEqualByComparingTo("10");
        assertThat(r.potentialSavings()).isEqualByComparingTo("60");
    }

    @Test
    void noOptimisableSpendMeansNoEfficiency() {
        VmOpportunity unpriced = new VmOpportunity("x", "e1", "g", "T", BigDecimal.ZERO, false, true,
                BigDecimal.TEN, BigDecimal.ZERO, false, false);
        Result r = PotentialSavingsService.summarize(List.of(unpriced), Set.of(), HALF);
        assertThat(r.efficiencyPercent()).isNull();
        assertThat(r.vmCount()).isZero();
        assertThat(PotentialSavingsService.summarize(List.of(), Set.of(), HALF).efficiencyPercent()).isNull();
    }
}
