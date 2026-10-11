package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/** An approval request as requesters and approvers see it (E25-T04). */
public record ApprovalRequestDTO(
        String requestId,
        String requestType,
        String environmentId,
        String environmentName,
        String requesterUserId,
        String requesterName,
        String scopeSummary,
        BigDecimal estimatedCost,
        Integer requestedHours,
        List<String> reasons,
        String approverMode,
        String status,
        String statusReason,
        Timestamp createdAt,
        Timestamp expiresAt,
        Timestamp decidedAt,
        String executionId,
        List<Approver> approvers,
        /** The signed-in user may approve or deny it now. */
        boolean canDecide,
        /** The signed-in user asked for it and it is still pending. */
        boolean canCancel) {

    /** decision APPROVED/DENIED, or null while undecided. */
    public record Approver(String userId, String name, String decision, String reason, Timestamp decidedAt) {}

    /** Approve/deny/cancel body. */
    public record DecisionRequest(String reason) {}

    /** What a held start answers (202). */
    public record Held(String approvalRequestId, String status, List<String> reasons, List<String> approvers,
                       BigDecimal estimatedCost) {}
}
