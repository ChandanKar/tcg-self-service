package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A month of realised savings (E17-T06): totals and causes from the ledger, potential savings and
 * Cost Efficiency % (current for this month, the month-end snapshot for past months), and how
 * close the cost estimates were to actual billing where actuals exist.
 */
public record SavingsSummaryDTO(
        String month,
        String environmentId,
        BigDecimal realisedNet,
        BigDecimal realisedGross,
        BigDecimal residualStorage,
        BigDecimal residualIpv4,
        BigDecimal commitmentAdjustment,
        List<SavingsBreakdownRowDTO> byCause,
        BigDecimal potentialSavings,
        BigDecimal optimisableSpend,
        BigDecimal efficiencyPercent,
        BigDecimal offHoursShare,
        ActualsCheck actualsCheck
) {
    /**
     * Estimate quality: estimated vs actual cost over the days that have actuals; the target is
     * within 15 %. Null fields when no day of the month has an actual cost.
     */
    public record ActualsCheck(int daysWithActuals, BigDecimal estimated, BigDecimal actual, BigDecimal variancePercent,
                               boolean withinTarget, String label) {
    }
}
