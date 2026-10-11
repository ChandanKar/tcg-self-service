package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/** An approval policy as the API shows it (E25-T03). */
public record ApprovalPolicyDTO(
        String policyId,
        String environmentId,
        String environmentName,
        String teamId,
        String teamName,
        String name,
        List<String> instanceTypePatterns,
        Integer minVcpu,
        Integer maxRuntimeHours,
        BigDecimal maxEstimatedCost,
        String approverMode,
        List<Approver> approvers,
        boolean active,
        Timestamp createdAt,
        Timestamp updatedAt) {

    /** type USER/TEAM/ENV_ADMINS; label is the user's or team's name. */
    public record Approver(String type, String approverId, String label) {}

    /** Create/update body. */
    public record Request(String environmentId, String teamId, String name, List<String> instanceTypePatterns,
                          Integer minVcpu, Integer maxRuntimeHours, BigDecimal maxEstimatedCost, String approverMode,
                          List<ApproverRequest> approvers, Boolean active) {}

    public record ApproverRequest(String type, String approverId) {}

    /** Dry run: what a start (or extension) would need. */
    public record EvaluateRequest(String environmentId, String groupId, String vmId, Integer hours, Boolean extension) {}
}
