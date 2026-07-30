package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One row in the rightsizing table. Candidacy rules (applied by the service, not here): a
 * scale-down candidate is CPU below a configurable threshold for a configurable number of
 * consecutive days; a scale-up candidate is CPU above a configurable threshold for a configurable
 * number of consecutive minutes. Both directions are CPU-only — memory and network signals are
 * not yet available at the granularity either rule needs.
 *
 * @param source {@code "compute-optimizer"} when AWS Compute Optimizer had a real recommendation
 *               for this VM, else {@code "cpu-threshold-rule"} (the original home-grown rule) —
 *               the two signals are never silently conflated. Compute Optimizer has no EKS
 *               managed-node-group recommendation type, so {@code AWS_EKS} VMs are always
 *               {@code "cpu-threshold-rule"}. Compute Optimizer integration is scale-down only.
 * @param findingLevel Compute Optimizer's own finding (e.g. {@code "OVER_PROVISIONED"}) when
 *                      {@code source} is {@code "compute-optimizer"}, else null.
 * @param direction {@code "SCALE_DOWN"} or {@code "SCALE_UP"}.
 * @param vmStatus the VM's current {@code VmStatus} (e.g. {@code "STOPPED"}) — the Apply action
 *                 that actually changes the instance type is only permitted when this is
 *                 {@code "STOPPED"}; carried here so the UI can gate the button without a second
 *                 lookup.
 */
public record RightsizingCandidateDTO(
        String vmId,
        String vmName,
        String environmentName,
        String currentInstanceType,
        String suggestedInstanceType,
        BigDecimal avgCpuUtilization,
        BigDecimal peakCpuUtilization,
        BigDecimal currentMonthlyCost,
        BigDecimal estimatedMonthlyCostAfter,
        BigDecimal estimatedMonthlySavings,
        Boolean costKnown,
        String source,
        String findingLevel,
        String direction,
        String vmStatus
) {}
