package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An EKS environment's pod-level cost over the last {@code days} ingested days (E26-T05): split
 * cost by namespace, each with its workloads, and the unused capacity (nodes paid for but not
 * requested). {@code share} is a percentage of the split cost plus unused cost.
 */
public record EksWorkloadCostDTO(
        String environmentId,
        int days,
        LocalDate from,
        LocalDate to,
        BigDecimal totalSplitCost,
        BigDecimal unusedCost,
        BigDecimal unusedShare,
        List<NamespaceCost> namespaces
) {
    public record NamespaceCost(String namespace, BigDecimal splitCost, BigDecimal unusedCost, BigDecimal share,
                                List<WorkloadCost> workloads) {}

    public record WorkloadCost(String namespace, String workload, BigDecimal splitCost, BigDecimal unusedCost, BigDecimal share) {}
}
