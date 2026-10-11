package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnvironmentLeaseParticipantRepository
        extends JpaRepository<EnvironmentLeaseParticipant, EnvironmentLeaseParticipantId> {

    /** Participants still in the lease, earliest first. */
    List<EnvironmentLeaseParticipant> findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(String leaseId);

    /** Lease ids the user is still an active participant of. */
    @Query("SELECT p.leaseId FROM EnvironmentLeaseParticipant p WHERE p.userId = :userId AND p.leftAt IS NULL")
    List<String> findActiveLeaseIdsByUserId(@Param("userId") String userId);
}
