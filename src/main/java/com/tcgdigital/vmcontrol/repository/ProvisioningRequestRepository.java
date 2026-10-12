package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

public interface ProvisioningRequestRepository extends JpaRepository<ProvisioningRequest, String> {

    List<ProvisioningRequest> findByStatusOrderByCreatedAtAsc(ProvisioningStatus status);

    List<ProvisioningRequest> findByStatusInOrderByCreatedAtAsc(Collection<ProvisioningStatus> statuses);

    List<ProvisioningRequest> findByRequestedByUserIdOrderByCreatedAtDesc(String userId);

    List<ProvisioningRequest> findByDestroyAtBeforeAndStatus(Timestamp before, ProvisioningStatus status);

    boolean existsByTemplateIdAndStatusIn(String templateId, Collection<ProvisioningStatus> statuses);

    boolean existsByEnvironmentNameIgnoreCaseAndStatusIn(String environmentName, Collection<ProvisioningStatus> statuses);

    /** Moves a request on only if it is still in {@code from} (E32-T03): 0 when someone decided first. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE ProvisioningRequest r SET r.status = :to, r.reviewedByUserId = :by, "
            + "r.reviewedAt = :at, r.reviewNotes = :notes, r.updatedAt = :at WHERE r.requestId = :id AND r.status = :from")
    int decide(@org.springframework.data.repository.query.Param("id") String requestId,
               @org.springframework.data.repository.query.Param("from") ProvisioningStatus from,
               @org.springframework.data.repository.query.Param("to") ProvisioningStatus to,
               @org.springframework.data.repository.query.Param("by") String userId,
               @org.springframework.data.repository.query.Param("at") Timestamp at,
               @org.springframework.data.repository.query.Param("notes") String notes);

    /** Moves a request between two statuses only if it is still in {@code from} (E32-T04). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("UPDATE ProvisioningRequest r SET r.status = :to, r.updatedAt = :at "
            + "WHERE r.requestId = :id AND r.status = :from")
    int moveIfStatus(@org.springframework.data.repository.query.Param("id") String requestId,
                     @org.springframework.data.repository.query.Param("from") ProvisioningStatus from,
                     @org.springframework.data.repository.query.Param("to") ProvisioningStatus to,
                     @org.springframework.data.repository.query.Param("at") Timestamp at);
}
