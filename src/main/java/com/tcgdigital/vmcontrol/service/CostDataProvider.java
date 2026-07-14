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

    record VmCostEstimate(
            boolean costKnown,
            BigDecimal hourlyRate,
            BigDecimal runtimeHours,
            long storageGib,
            BigDecimal cost
    ) {}
}
