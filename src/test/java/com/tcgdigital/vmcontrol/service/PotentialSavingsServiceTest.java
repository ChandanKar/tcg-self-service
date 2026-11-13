package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import com.tcgdigital.vmcontrol.service.CostEstimationService.VmOpportunity;
import com.tcgdigital.vmcontrol.service.PotentialSavingsService.PotentialCause;
import com.tcgdigital.vmcontrol.service.PotentialSavingsService.Result;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** De-duplicated potential savings and Cost Efficiency % (E17-T05), built from Cost management's parts (U23-T06). */
class PotentialSavingsServiceTest {

    private static VmOpportunity vm(String id, String env, String cost, boolean idle, String idleCost, String rightsizing,
                                    boolean production, boolean lease) {
        return new VmOpportunity(id, env, "g-" + id, "Team", new BigDecimal(cost), true, idle,
                new BigDecimal(idleCost), new BigDecimal(rightsizing), production, lease);
    }

    @Test
    void anIdleVmCountsItsLargestOpportunityOnce() {
        // idle $50 vs rightsizing $20 -> $50, not $70.
        Result r = PotentialSavingsService.summarize(List.of(vm("a", "e1", "100", true, "50", "20", false, false)), null);
        assertThat(r.potentialSavings()).isEqualByComparingTo("50");
        assertThat(r.byCause().get(PotentialCause.IDLE)).isEqualByComparingTo("50");
        assertThat(r.byCause().get(PotentialCause.RIGHTSIZING)).isEqualByComparingTo("0");
    }

    @Test
    void efficiencyIsOneMinusPotentialOverOptimisable() {
        // $1,000 optimisable, $170 potential -> 83.0 %.
        Result r = PotentialSavingsService.summarize(List.of(
                vm("a", "e1", "600", true, "120", "0", false, false),
                vm("b", "e1", "400", false, "0", "50", false, false)), null);
        assertThat(r.optimisableSpend()).isEqualByComparingTo("1000");
        assertThat(r.potentialSavings()).isEqualByComparingTo("170");
        assertThat(r.efficiencyPercent()).isEqualByComparingTo("83.0");
    }

    @Test
    void stillOnTheTableIsIdlePlusRightsizingPlusOpenWasteAndNothingElse() {
        // U23-T06: staging's $4,654 was mostly an assumed off-hours half of every unscheduled VM, shown nowhere.
        Result r = PotentialSavingsService.summarize(List.of(
                vm("free", "e1", "100", false, "0", "10", false, false),  // unscheduled, not production: rightsizing only
                vm("idle", "e1", "200", true, "144", "30", false, false), // idle beats rightsizing
                vm("busy", "e2", "300", false, "0", "0", false, false)),
                new BigDecimal("25"));
        assertThat(r.byCause().get(PotentialCause.IDLE)).isEqualByComparingTo("144");
        assertThat(r.byCause().get(PotentialCause.RIGHTSIZING)).isEqualByComparingTo("10");
        assertThat(r.byCause().get(PotentialCause.WASTE)).isEqualByComparingTo("25");
        assertThat(r.potentialSavings()).isEqualByComparingTo("179");
        assertThat(r.byCause().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(r.potentialSavings());
        assertThat(r.optimisableSpend()).isEqualByComparingTo("625"); // the waste is spend too
        assertThat(r.wasteChecked()).isTrue();
    }

    @Test
    void whileTheWasteChecksAreOffWasteIsNotAPart() {
        Result r = PotentialSavingsService.summarize(List.of(vm("a", "e1", "100", false, "0", "10", false, false)), null);
        assertThat(r.byCause()).doesNotContainKey(PotentialCause.WASTE);
        assertThat(r.wasteChecked()).isFalse();
        assertThat(r.potentialSavings()).isEqualByComparingTo("10");
    }

    @Test
    void openWasteCountsForTheFleetAndForItsOwnEnvironmentOnlyWhileTheChecksAreOn() {
        CostEstimationService costs = mock(CostEstimationService.class);
        when(costs.listVmOpportunities()).thenReturn(List.of(vm("a", "e1", "100", false, "0", "10", false, false)));
        WasteFindingRepository findings = mock(WasteFindingRepository.class);
        when(findings.openMonthlyByEnvironment()).thenReturn(List.of(
                new Object[] {"e1", new BigDecimal("20")}, new Object[] {null, new BigDecimal("5")}));
        SettingsService settings = mock(SettingsService.class);
        PotentialSavingsService service = new PotentialSavingsService(costs, null, null, null, null);
        service.setWaste(findings, settings);

        when(settings.getBoolean("waste.sweep.enabled", false)).thenReturn(false);
        assertThat(service.compute(null).potentialSavings()).isEqualByComparingTo("10");

        when(settings.getBoolean("waste.sweep.enabled", false)).thenReturn(true);
        assertThat(service.compute(null).potentialSavings()).isEqualByComparingTo("35");
        assertThat(service.compute("e1").byCause().get(PotentialCause.WASTE)).isEqualByComparingTo("20");
        assertThat(service.compute("e2").byCause().get(PotentialCause.WASTE)).isEqualByComparingTo("0");
    }

    @Test
    void noOptimisableSpendMeansNoEfficiency() {
        VmOpportunity unpriced = new VmOpportunity("x", "e1", "g", "T", BigDecimal.ZERO, false, true,
                BigDecimal.TEN, BigDecimal.ZERO, false, false);
        Result r = PotentialSavingsService.summarize(List.of(unpriced), null);
        assertThat(r.efficiencyPercent()).isNull();
        assertThat(r.vmCount()).isZero();
        assertThat(PotentialSavingsService.summarize(List.of(), null).efficiencyPercent()).isNull();
    }
}
