package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One row in the rightsizing table. Candidacy rules (applied by the service, not here): a
 * scale-down candidate is CPU below a configurable threshold for a configurable number of
 * consecutive days; a scale-up candidate is CPU above a configurable threshold for a configurable
 * number of consecutive minutes. Since E30-T03 memory counts too (rightsizing.memory.enabled):
 * a scale-down also needs every recent day's memory peak below its threshold (when the CloudWatch
 * agent reports memory; otherwise the row says MEMORY_UNKNOWN), memory above its threshold in
 * every sample of the scale-up window makes a scale-up candidate, and a smaller type that would
 * not hold the observed memory peak plus headroom is dropped (MEMORY_WOULD_NOT_FIT, the row stays
 * listed with no suggestion so admins see why).
 *
 * @param source {@code "compute-optimizer"} when AWS Compute Optimizer had a real recommendation
 *               for this VM, else {@code "cpu-threshold-rule"} (the original home-grown rule) —
 *               the two signals are never silently conflated. Compute Optimizer has no EKS
 *               managed-node-group recommendation type, so {@code AWS_EKS} VMs are always
 *               {@code "cpu-threshold-rule"}. Compute Optimizer integration is scale-down only.
 * @param findingLevel Compute Optimizer's own finding (e.g. {@code "OVER_PROVISIONED"}) when
 *                      {@code source} is {@code "compute-optimizer"}, else null.
 * @param direction {@code "SCALE_DOWN"}, {@code "SCALE_UP"} or (E30-T05) {@code "MODERNIZE"}: a VM on an
 *                  older generation whose same-architecture newer type costs the same or less.
 * @param alternatives every option worth knowing (E30-T05): the chosen one, the newer-generation and
 *                     Graviton equivalents, and every Compute Optimizer option, each priced.
 * @param agreement {@code AGREE}, {@code ONLY_AWS}, {@code ONLY_RULE} or {@code DISAGREE}: whether
 *                  Compute Optimizer and the local rules point the same way.
 * @param applicable whether Apply may change the VM to the suggested type ({@code applicableReason}
 *                   says why not: an EKS node group, another CPU architecture, no suggestion).
 * @param reasons why the VM is a candidate: CPU_LOW, CPU_HIGH, MEMORY_LOW, MEMORY_HIGH,
 *                MEMORY_UNKNOWN, MEMORY_WOULD_NOT_FIT.
 * @param vmStatus the VM's current {@code VmStatus} (e.g. {@code "STOPPED"}) — the Apply action
 *                 that actually changes the instance type is only permitted when this is
 *                 {@code "STOPPED"}; carried here so the UI can gate the button without a second
 *                 lookup.
 */
public record RightsizingCandidateDTO(
        String vmId,
        String vmName,
        String environmentId,
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
        String vmStatus,
        /** The current or the suggested rate is another region's (E08-T07). */
        Boolean priceApproximate,
        /** The Jira issue tracking this recommendation, if any (E24-T07). */
        String jiraKey,
        String jiraUrl,
        /** Nodes of an EKS node group (savings already cover them all); null for a single VM (E26-T02). */
        Integer nodeCount,
        /** Memory utilisation over the cost window; null without the CloudWatch agent (E30-T03). */
        BigDecimal avgMemoryUtilization,
        BigDecimal peakMemoryUtilization,
        Boolean memoryDataAvailable,
        java.util.List<String> reasons,
        java.util.List<RightsizingOptionDTO> alternatives,
        String agreement,
        Boolean applicable,
        String applicableReason
) {
    public RightsizingCandidateDTO(String vmId, String vmName, String environmentId, String environmentName,
                                   String currentInstanceType, String suggestedInstanceType,
                                   BigDecimal avgCpuUtilization, BigDecimal peakCpuUtilization,
                                   BigDecimal currentMonthlyCost, BigDecimal estimatedMonthlyCostAfter,
                                   BigDecimal estimatedMonthlySavings, Boolean costKnown, String source,
                                   String findingLevel, String direction, String vmStatus, Boolean priceApproximate) {
        this(vmId, vmName, environmentId, environmentName, currentInstanceType, suggestedInstanceType,
                avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, null, null, null,
                null, null, false, java.util.List.of(), java.util.List.of(), null, suggestedInstanceType != null, null);
    }

    public RightsizingCandidateDTO withJira(String key, String url) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, key, url, nodeCount,
                avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons, alternatives, agreement, applicable,
                applicableReason);
    }

    public RightsizingCandidateDTO withNodeCount(Integer nodes) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, jiraKey, jiraUrl, nodes,
                avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons, alternatives, agreement, applicable,
                applicableReason);
    }

    public RightsizingCandidateDTO withMemory(BigDecimal avgMemory, BigDecimal peakMemory, boolean available,
                                              java.util.List<String> why) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, jiraKey, jiraUrl,
                nodeCount, avgMemory, peakMemory, available, why == null ? java.util.List.of() : java.util.List.copyOf(why),
                alternatives, agreement, applicable, applicableReason);
    }

    public RightsizingCandidateDTO withRecommendation(java.util.List<RightsizingOptionDTO> options, String agree,
                                                      boolean canApply, String whyNot) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, jiraKey, jiraUrl,
                nodeCount, avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons,
                options == null ? java.util.List.of() : java.util.List.copyOf(options), agree, canApply, whyNot);
    }

    /** Whether Apply may change the VM to {@code type}: the suggestion or an applicable alternative (E30-T05). */
    public boolean canApply(String type) {
        if (type == null) {
            return false;
        }
        if (type.equals(suggestedInstanceType)) {
            return !Boolean.FALSE.equals(applicable);
        }
        return alternatives != null && alternatives.stream().anyMatch(o -> o.applicable() && type.equals(o.instanceType()));
    }

    public RightsizingCandidateDTO(String vmId, String vmName, String environmentId, String environmentName,
                                   String currentInstanceType, String suggestedInstanceType,
                                   BigDecimal avgCpuUtilization, BigDecimal peakCpuUtilization,
                                   BigDecimal currentMonthlyCost, BigDecimal estimatedMonthlyCostAfter,
                                   BigDecimal estimatedMonthlySavings, Boolean costKnown, String source,
                                   String findingLevel, String direction, String vmStatus) {
        this(vmId, vmName, environmentId, environmentName, currentInstanceType, suggestedInstanceType,
                avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, false);
    }
}
