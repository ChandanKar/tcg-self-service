package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostSplitRule;
import com.tcgdigital.vmcontrol.model.CostSplitShare;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared-cost split arithmetic (E23-T05): every method sums exactly to the amount. */
class CostSplitServiceTest {

    private final CostSplitService service = new CostSplitService(null, null, null, null);

    private static CostSplitRule rule(CostSplitRule.Method method, CostSplitShare... shares) {
        CostSplitRule rule = new CostSplitRule();
        rule.setMethod(method);
        rule.setShares(Arrays.asList(shares));
        return rule;
    }

    private static CostSplitShare share(String team, String percent) {
        return new CostSplitShare(team, percent == null ? null : new BigDecimal(percent));
    }

    private static BigDecimal sum(Map<String, BigDecimal> split) {
        return split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fixedPercentGivesTheRemainderToTheLargestShare() {
        Map<String, BigDecimal> split = service.split(new BigDecimal("100.01"),
                rule(CostSplitRule.Method.FIXED_PERCENT, share("x", "60.00"), share("y", "40.00")), Map.of());
        assertThat(split).containsExactly(Map.entry("x", new BigDecimal("60.01")), Map.entry("y", new BigDecimal("40.00")));

        Map<String, BigDecimal> thirds = service.split(new BigDecimal("10.00"),
                rule(CostSplitRule.Method.FIXED_PERCENT, share("a", "33.33"), share("b", "33.34"), share("c", "33.33")), Map.of());
        assertThat(sum(thirds)).isEqualByComparingTo("10.00");
        assertThat(thirds.get("b")).isEqualByComparingTo("3.34");
    }

    @Test
    void evenGivesTheRemainderToTheFirstTeam() {
        Map<String, BigDecimal> split = service.split(new BigDecimal("100.00"),
                rule(CostSplitRule.Method.EVEN, share("a", null), share("b", null), share("c", null)), Map.of());
        assertThat(split).containsExactly(Map.entry("a", new BigDecimal("33.34")),
                Map.entry("b", new BigDecimal("33.33")), Map.entry("c", new BigDecimal("33.33")));

        Map<String, BigDecimal> tiny = service.split(new BigDecimal("0.02"),
                rule(CostSplitRule.Method.EVEN, share("a", null), share("b", null), share("c", null)), Map.of());
        assertThat(sum(tiny)).isEqualByComparingTo("0.02");
    }

    @Test
    void proportionalDirectWeighsByDirectCostAndFallsBackToEven() {
        CostSplitRule rule = rule(CostSplitRule.Method.PROPORTIONAL_DIRECT, share("x", null), share("y", null), share("z", null));
        Map<String, BigDecimal> split = service.split(new BigDecimal("100.00"), rule,
                Map.of("x", new BigDecimal("300"), "y", new BigDecimal("100"), "z", new BigDecimal("-5")));
        assertThat(split).containsExactly(Map.entry("x", new BigDecimal("75.00")),
                Map.entry("y", new BigDecimal("25.00")), Map.entry("z", new BigDecimal("0.00")));

        Map<String, BigDecimal> uneven = service.split(new BigDecimal("10.00"), rule,
                Map.of("x", BigDecimal.ONE, "y", BigDecimal.ONE, "z", BigDecimal.ONE));
        assertThat(sum(uneven)).isEqualByComparingTo("10.00");

        Map<String, BigDecimal> fallback = service.split(new BigDecimal("90.00"), rule, Map.of());
        assertThat(fallback.values()).containsOnly(new BigDecimal("30.00"));
    }

    @Test
    void amountsAreRoundedToCentsAndARuleNeedsTeams() {
        Map<String, BigDecimal> split = service.split(new BigDecimal("10.005"),
                rule(CostSplitRule.Method.EVEN, share("a", null), share("b", null)), null);
        assertThat(sum(split)).isEqualByComparingTo("10.01");

        assertThatThrownBy(() -> service.split(BigDecimal.TEN, rule(CostSplitRule.Method.EVEN), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.split(null, rule(CostSplitRule.Method.EVEN, share("a", null)), Map.of()))
                .isEqualTo(Map.of("a", new BigDecimal("0.00")));
        assertThat(List.copyOf(service.split(BigDecimal.ONE,
                rule(CostSplitRule.Method.FIXED_PERCENT, share("a", "50"), share("b", "50")), Map.of()).keySet()))
                .containsExactly("a", "b");
    }
}
