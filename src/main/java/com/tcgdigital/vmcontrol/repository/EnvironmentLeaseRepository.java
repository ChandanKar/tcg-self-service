package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnvironmentLeaseRepository extends JpaRepository<EnvironmentLease, String> {

    /** The environment's ACTIVE lease, if any (at most one, enforced by V42's unique index). */
    @Query("SELECT l FROM EnvironmentLease l WHERE l.environment.environmentId = :environmentId "
            + "AND l.status = com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE")
    Optional<EnvironmentLease> findActiveByEnvironmentId(@Param("environmentId") String environmentId);

    /** ACTIVE leases the user is still an active participant of, ending soonest first (My Account, E15-T07). */
    @Query("SELECT l FROM EnvironmentLease l JOIN FETCH l.environment WHERE l.status = "
            + "com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE AND l.leaseId IN (SELECT p.leaseId FROM "
            + "EnvironmentLeaseParticipant p WHERE p.userId = :userId AND p.leftAt IS NULL) ORDER BY l.endsAt")
    List<EnvironmentLease> findActiveForParticipant(@Param("userId") String userId);

    /** Ids of ACTIVE leases whose end time has passed (expiry sweep, E15-T05). */
    @Query("SELECT l.leaseId FROM EnvironmentLease l WHERE l.status = com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE "
            + "AND l.endsAt <= :now ORDER BY l.endsAt")
    List<String> findOverdueIds(@Param("now") Timestamp now);

    /** ACTIVE leases ending in (start, end] (warning thresholds, E15-T04). */
    @Query("SELECT l FROM EnvironmentLease l JOIN FETCH l.environment WHERE l.status = "
            + "com.tcgdigital.vmcontrol.model.LeaseStatus.ACTIVE AND l.endsAt > :start AND l.endsAt <= :end")
    List<EnvironmentLease> findActiveEndingBetween(@Param("start") Timestamp start, @Param("end") Timestamp end);
}
