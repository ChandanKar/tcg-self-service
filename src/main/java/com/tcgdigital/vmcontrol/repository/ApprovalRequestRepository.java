package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.List;

/** Approval requests (E25-T04). */
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    List<ApprovalRequest> findByRequesterUserIdOrderByCreatedAtDesc(String requesterUserId);

    @Query("SELECT DISTINCT r FROM ApprovalRequest r JOIN r.approverUserIds a WHERE a = :userId ORDER BY r.createdAt DESC")
    List<ApprovalRequest> findForApprover(@Param("userId") String userId);

    /**
     * Moves a request on from PENDING; 1 for the caller that won, 0 when someone else already
     * decided it (so an approval executes at most once).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ApprovalRequest r SET r.status = :status, r.statusReason = :reason, r.decidedAt = :now, "
            + "r.version = r.version + 1 WHERE r.requestId = :id AND r.status = com.tcgdigital.vmcontrol.model.ApprovalRequest.Status.PENDING")
    int decideIfPending(@Param("id") String id, @Param("status") ApprovalRequest.Status status, @Param("reason") String reason,
                        @Param("now") Timestamp now);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.status = com.tcgdigital.vmcontrol.model.ApprovalRequest.Status.PENDING "
            + "AND r.requestType = :type")
    List<ApprovalRequest> findPendingOfType(@Param("type") ApprovalRequest.Type type);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.status = com.tcgdigital.vmcontrol.model.ApprovalRequest.Status.PENDING "
            + "AND r.expiresAt < :now")
    List<ApprovalRequest> findExpired(@Param("now") Timestamp now);
}
