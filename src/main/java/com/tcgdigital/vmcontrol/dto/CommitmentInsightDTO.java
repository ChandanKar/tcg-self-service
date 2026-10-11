package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How schedules and commitments interact (E27-T02), for display only. Every figure is an
 * estimate from the static pricing reference; a figure without data is null, never zero.
 * <ul>
 *   <li>{@code scheduledHoursCoveredValue}: what stopped hours would have saved but did not,
 *       because commitments had already paid for them (savings ledger commitment adjustments).</li>
 *   <li>{@code grossScheduledSaving}: what the same stops would have saved with no commitments.</li>
 *   <li>{@code alwaysOnHourlyFloor}: On-Demand $/h of the AWS VMs running in the quietest hours
 *       (10th percentile hour), the spend that is safe to commit to.</li>
 * </ul>
 */
public record CommitmentInsightDTO(
        int windowDays,
        Coverage coverage,
        BigDecimal scheduledHoursCoveredValue,
        BigDecimal grossScheduledSaving,
        BigDecimal scheduledAvoidedHours,
        BigDecimal alwaysOnHourlyFloor,
        int floorVmCount,
        String floorNote,
        List<Recommendation> recommendations,
        List<RiskFlag> riskFlags
) {
    public record Coverage(LocalDate snapshotDate, BigDecimal riCoveragePercent, BigDecimal spCoveragePercent,
                           BigDecimal riUtilizationPercent, BigDecimal spUtilizationPercent) {}

    public record Recommendation(String type, String term, String paymentOption, Integer lookbackDays,
                                 BigDecimal hourlyCommitment, BigDecimal upfrontCost, BigDecimal estimatedMonthlySavings,
                                 BigDecimal estimatedSavingsPercent, BigDecimal estimatedOnDemandCost,
                                 LocalDate generatedOn, JsonNode details) {}

    /** {@code code}: OVER_COMMIT, UNDER_UTILISED or SCHEDULES_ALREADY_COVERED. */
    public record RiskFlag(String code, String message, String recommendationType) {}
}
