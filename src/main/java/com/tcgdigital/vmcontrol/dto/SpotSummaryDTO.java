package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An environment's Spot picture (E31-T08): its policy, what the viewer may do, its VMs and node
 * groups with their Spot state, interruptions in the last 30 days and what Spot saved. U11-T08 adds
 * each row's size and saving (measured on Spot, estimated otherwise), the recent interruptions with
 * how long the standby ran, and the fallback's grace minutes.
 */
public record SpotSummaryDTO(
        String environmentId,
        String policy,
        boolean production,
        boolean canAdminister,
        boolean conversionEnabled,
        int spotVmCount,
        int standbyCount,
        boolean fallbackActive,
        long interruptions30d,
        BigDecimal spotSavingsMonth,
        List<SpotVm> vms,
        List<Interruption> recentInterruptions,
        int fallbackGraceMinutes
) {
    /** One VM or node group and its Spot state. */
    public record SpotVm(
            String vmId,
            String name,
            String kind,
            String purchaseOption,
            boolean excluded,
            boolean standby,
            String partnerName,
            boolean fallbackActive,
            String status,
            boolean convertible,
            boolean revertible,
            String instanceType,
            Integer nodeCount,
            BigDecimal monthlySavings,
            boolean savingsEstimated
    ) {
    }

    /**
     * One time AWS took Spot capacity back (U11-T08): which node group, when, and how long its
     * on-demand standby ran (null while it is still running).
     */
    public record Interruption(String vmName, java.time.Instant at, Long standbyMinutes) {
    }
}
