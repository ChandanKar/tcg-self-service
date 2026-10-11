package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.LeaseExtensionRequest;
import com.tcgdigital.vmcontrol.model.LeaseExtensionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeaseExtensionRequestRepository extends JpaRepository<LeaseExtensionRequest, String> {

    Optional<LeaseExtensionRequest> findFirstByLeaseIdAndStatus(String leaseId, LeaseExtensionStatus status);

    List<LeaseExtensionRequest> findByLeaseIdAndStatusOrderByCreatedAtAsc(String leaseId, LeaseExtensionStatus status);

    /**
     * Decide a request only while it is still PENDING; 0 rows means someone decided it first
     * (two owners approving at once get one 200 and one 409).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LeaseExtensionRequest r SET r.status = :status, r.reviewedByUserId = :reviewerId, "
            + "r.reviewedAt = :reviewedAt, r.reviewNotes = :notes WHERE r.requestId = :requestId "
            + "AND r.status = com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING")
    int transitionFromPending(@Param("requestId") String requestId, @Param("status") LeaseExtensionStatus status,
                              @Param("reviewerId") String reviewerId, @Param("reviewedAt") Timestamp reviewedAt,
                              @Param("notes") String notes);

    /** Pending requests of a lease that ended become EXPIRED (E15-T05). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE LeaseExtensionRequest r SET r.status = com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.EXPIRED "
            + "WHERE r.leaseId = :leaseId AND r.status = com.tcgdigital.vmcontrol.model.LeaseExtensionStatus.PENDING")
    int expirePendingForLease(@Param("leaseId") String leaseId);
}
