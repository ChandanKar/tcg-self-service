package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An environment's Spot picture (E31-T08): its policy, what the viewer may do, its VMs and node
 * groups with their Spot state, interruptions in the last 30 days and what Spot saved.
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
        List<SpotVm> vms
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
            boolean revertible
    ) {
    }
}
