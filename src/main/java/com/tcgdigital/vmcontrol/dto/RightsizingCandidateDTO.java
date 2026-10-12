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
 * @param direction {@code "SCALE_DOWN"} or {@code "SCALE_UP"}.
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
        java.util.List<String> reasons
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
                null, null, false, java.util.List.of());
    }

    public RightsizingCandidateDTO withJira(String key, String url) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, key, url, nodeCount,
                avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons);
    }

    public RightsizingCandidateDTO withNodeCount(Integer nodes) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, jiraKey, jiraUrl, nodes,
                avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons);
    }

    public RightsizingCandidateDTO withMemory(BigDecimal avgMemory, BigDecimal peakMemory, boolean available,
                                              java.util.List<String> why) {
        return new RightsizingCandidateDTO(vmId, vmName, environmentId, environmentName, currentInstanceType,
                suggestedInstanceType, avgCpuUtilization, peakCpuUtilization, currentMonthlyCost, estimatedMonthlyCostAfter,
                estimatedMonthlySavings, costKnown, source, findingLevel, direction, vmStatus, priceApproximate, jiraKey, jiraUrl,
                nodeCount, avgMemory, peakMemory, available, why == null ? java.util.List.of() : java.util.List.copyOf(why));
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
