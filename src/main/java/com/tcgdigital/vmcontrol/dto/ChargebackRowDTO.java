package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One team's monthly chargeback (E23-T06): its direct environment cost plus its share of split
 * environments. teamId is null for the "Unassigned" row and for the totals row.
 */
public record ChargebackRowDTO(
        String teamId,
        String teamName,
        String costCenter,
        BigDecimal directCost,
        BigDecimal sharedAllocated,
        BigDecimal total,
        /** Share of the contributing environment-days that had actual billing, 0-100. */
        BigDecimal actualCoveragePct,
        List<EnvironmentCharge> environments) {

    /** An environment's contribution to the row; shared marks a split environment's allocation. */
    public record EnvironmentCharge(String environmentId, String environmentName, BigDecimal amount, boolean shared) {}
}
