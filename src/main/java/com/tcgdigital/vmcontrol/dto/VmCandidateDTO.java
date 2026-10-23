package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.dto.json.AdminOnly;

/**
 * An EC2 instance or RDS database offered for "Add VMs to &lt;environment&gt;" (U11-T01,
 * RegisterVms.dc.html), with what it is to that environment. People pick it by name and add it with
 * {@code ref}; the cloud id is for administrators only.
 *
 * @param ref               opaque, short-lived reference to send back in a batch (U01-T02)
 * @param providerVmId      administrators only: the instance id, or db:/cluster: for RDS
 * @param name              the Name tag (RDS: the identifier); null when the instance has none
 * @param status            AVAILABLE, DISCOVERED_UNGROUPED (found by discovery here, not in a group yet:
 *                          adding moves it), IN_ENVIRONMENT, STALE (registered, gone from AWS), REMOVED or
 *                          EKS_NODE (a worker node of an EKS cluster: its node group owns it, U20-T05)
 * @param inEnvironmentName where it is registered; "another environment" when the caller cannot view it
 * @param inGroupName       its group there, when the caller can view it
 * @param note              a sentence for the list: "Found by discovery, not in a group", "Already in CRM UAT", …
 */
public record VmCandidateDTO(
        String ref,
        @AdminOnly String providerVmId,
        String name,
        String instanceType,
        String zone,
        String privateIp,
        String state,
        Status status,
        String inEnvironmentName,
        String inGroupName,
        String note) {

    public enum Status { AVAILABLE, DISCOVERED_UNGROUPED, IN_ENVIRONMENT, STALE, REMOVED, EKS_NODE }

    /** Whether a batch may take it. */
    public boolean canAdd() {
        return status == Status.AVAILABLE || status == Status.DISCOVERED_UNGROUPED;
    }
}
