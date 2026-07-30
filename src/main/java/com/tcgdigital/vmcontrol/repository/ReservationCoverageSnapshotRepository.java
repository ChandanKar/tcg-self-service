package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ReservationCoverageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationCoverageSnapshotRepository extends JpaRepository<ReservationCoverageSnapshot, String> {

    Optional<ReservationCoverageSnapshot> findBySnapshotDate(Date snapshotDate);

    List<ReservationCoverageSnapshot> findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(Date sinceDate);

    Optional<ReservationCoverageSnapshot> findTopByOrderBySnapshotDateDesc();
}
