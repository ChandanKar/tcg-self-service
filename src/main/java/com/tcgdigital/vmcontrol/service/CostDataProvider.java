package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Abstracts "how much does this VM cost" so an estimated pricing model and a future real
 * billing integration (e.g. AWS Cost Explorer) can share the same read-side services. Exactly
 * one implementation is active at a time, selected via the {@code cost.provider} property —
 * no multi-provider registry, since only one cost source is ever active at once.
 */
public interface CostDataProvider {

    /**
     * Cost estimate per VM over {@code windowStart}..{@code windowEnd}, keyed by vmId. Every VM
     * in {@code vms} is guaranteed an entry, with {@code costKnown=false} where pricing couldn't
     * be resolved rather than a fabricated figure.
     */
    Map<String, VmCostEstimate> estimateCosts(List<Vm> vms, Timestamp windowStart, Timestamp windowEnd);

    /**
     * Charges that belong to an environment rather than to one of its VMs, keyed by environmentId,
     * over the same window (E26-T03: the EKS control-plane fee). Environments without any are absent.
     */
    default Map<String, BigDecimal> environmentFees(List<Vm> vms, Timestamp windowStart, Timestamp windowEnd) {
        return Map.of();
    }

    /** {@code priceApproximate}: the hourly rate is another region's (E08-T07). */
    record VmCostEstimate(
            boolean costKnown,
            BigDecimal hourlyRate,
            BigDecimal runtimeHours,
            long storageGib,
            BigDecimal cost,
            boolean priceApproximate,
            /** What the same hours would cost on demand, for a Spot VM priced at its Spot rate (E31-T02); else null. */
            BigDecimal onDemandEquivalentCost
    ) {
        public VmCostEstimate(boolean costKnown, BigDecimal hourlyRate, BigDecimal runtimeHours, long storageGib,
                              BigDecimal cost) {
            this(costKnown, hourlyRate, runtimeHours, storageGib, cost, false, null);
        }

        public VmCostEstimate(boolean costKnown, BigDecimal hourlyRate, BigDecimal runtimeHours, long storageGib,
                              BigDecimal cost, boolean priceApproximate) {
            this(costKnown, hourlyRate, runtimeHours, storageGib, cost, priceApproximate, null);
        }
    }
}
