package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One row in the "Idle & waste, ranked by cost impact" table.
 */
public record IdleWasteRowDTO(
        String vmId,
        String vmName,
        String environmentId,
        String environmentName,
        String groupName,
        BigDecimal monthlyCost,
        Boolean costKnown,
        Integer idleDurationMinutes,
        Timestamp idleSince,
        BigDecimal latestCpuUtilization,
        BigDecimal monthlyIdleCost,
        /** The Jira issue tracking this VM's idle cost, if any (E24-T07). */
        String jiraKey,
        String jiraUrl,
        /** Nodes of an EKS node group (its costs already cover them all); null for a single VM (E26-T02). */
        Integer nodeCount
) {
    public IdleWasteRowDTO(String vmId, String vmName, String environmentId, String environmentName, String groupName,
                           BigDecimal monthlyCost, Boolean costKnown, Integer idleDurationMinutes, Timestamp idleSince,
                           BigDecimal latestCpuUtilization, BigDecimal monthlyIdleCost) {
        this(vmId, vmName, environmentId, environmentName, groupName, monthlyCost, costKnown, idleDurationMinutes, idleSince,
                latestCpuUtilization, monthlyIdleCost, null, null, null);
    }

    public IdleWasteRowDTO withJira(String key, String url) {
        return new IdleWasteRowDTO(vmId, vmName, environmentId, environmentName, groupName, monthlyCost, costKnown,
                idleDurationMinutes, idleSince, latestCpuUtilization, monthlyIdleCost, key, url, nodeCount);
    }

    public IdleWasteRowDTO withNodeCount(Integer nodes) {
        return new IdleWasteRowDTO(vmId, vmName, environmentId, environmentName, groupName, monthlyCost, costKnown,
                idleDurationMinutes, idleSince, latestCpuUtilization, monthlyIdleCost, jiraKey, jiraUrl, nodes);
    }
}
