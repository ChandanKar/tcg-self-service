package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.VmAdviceDTO;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * U08-T06: a VM's advice from the cost estimate and the stored rightsizing candidate, without any
 * cloud call: cost this month and rate when priced, "—" (null) when not, and the recommended size
 * with its savings and whether Compute Optimizer agrees.
 */
class VmAdviceServiceTest {

    private final VmRepository vms = mock(VmRepository.class);
    private final CostDataProvider costs = mock(CostDataProvider.class);
    private final CostEstimationService estimation = mock(CostEstimationService.class);
    private final VmAdviceService service = new VmAdviceService(vms, costs, estimation);

    private Vm vm(String id) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setName("pay-api-01");
        vm.setPurchaseOption(PurchaseOption.ON_DEMAND);
        when(vms.findById(id)).thenReturn(Optional.of(vm));
        return vm;
    }

    private static CostDataProvider.VmCostEstimate estimate(boolean known, String cost, String rate) {
        return new CostDataProvider.VmCostEstimate(known, rate == null ? null : new BigDecimal(rate), BigDecimal.TEN, 100,
                cost == null ? null : new BigDecimal(cost), false, null);
    }

    @Test
    void costThisMonthAndAScaleDownTheOptimizerAgreesWith() {
        vm("v1");
        service.setClock(Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC));
        when(costs.estimateCosts(anyList(), any(), any())).thenReturn(Map.of("v1", estimate(true, "38.204", "0.096")));
        RightsizingCandidateDTO candidate = new RightsizingCandidateDTO("v1", "pay-api-01", "e1", "Payments Dev",
                "m6i.large", "m6i.medium", new BigDecimal("9.5"), new BigDecimal("18"), new BigDecimal("70"),
                new BigDecimal("39"), new BigDecimal("31.004"), true, "cpu-threshold-rule", "LOW", "SCALE_DOWN", "RUNNING", false)
                .withRecommendation(List.of(), "AGREE", true, null);
        when(estimation.findCandidate("v1")).thenReturn(Optional.of(candidate));

        VmAdviceDTO advice = service.advice("v1");

        assertThat(advice.monthToDateCost()).isEqualByComparingTo("38.20");
        assertThat(advice.hourlyRate()).isEqualByComparingTo("0.0960");
        assertThat(advice.purchaseOption()).isEqualTo("ON_DEMAND");
        assertThat(advice.rightsizing().recommendedType()).isEqualTo("m6i.medium");
        assertThat(advice.rightsizing().direction()).isEqualTo("SCALE_DOWN");
        assertThat(advice.rightsizing().monthlySavings()).isEqualByComparingTo("31.00");
        assertThat(advice.rightsizing().cpuPeak()).isEqualByComparingTo("18");
        assertThat(advice.rightsizing().optimizerAgrees()).isTrue();
    }

    @Test
    void noPriceAndNoCandidateLeaveTheFieldsEmpty() {
        vm("v2");
        when(costs.estimateCosts(anyList(), any(), any())).thenReturn(Map.of("v2", estimate(false, null, null)));
        when(estimation.findCandidate("v2")).thenReturn(Optional.empty());

        VmAdviceDTO advice = service.advice("v2");

        assertThat(advice.monthToDateCost()).isNull();
        assertThat(advice.hourlyRate()).isNull();
        assertThat(advice.rightsizing()).isNull();
    }

    @Test
    void aCandidateThatKeepsTheSameSizeIsNoAdvice() {
        RightsizingCandidateDTO same = new RightsizingCandidateDTO("v3", "x", "e", "E", "t3.small", "t3.small",
                null, null, null, null, null, false, "newer-generation", null, "MODERNIZE", "RUNNING", false);
        assertThat(VmAdviceService.rightsizing(same)).isNull();
    }
}
