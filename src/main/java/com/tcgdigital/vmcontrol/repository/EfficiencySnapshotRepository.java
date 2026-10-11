package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EfficiencySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EfficiencySnapshotRepository extends JpaRepository<EfficiencySnapshot, String> {

    /** Fleet trend (E17-T05). */
    List<EfficiencySnapshot> findByEnvironmentIdIsNullAndSnapshotDateBetweenOrderBySnapshotDateAsc(LocalDate start, LocalDate end);

    /** One environment's trend. */
    List<EfficiencySnapshot> findByEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String environmentId, LocalDate start, LocalDate end);

    /** A day is rewritten as a whole. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EfficiencySnapshot s WHERE s.snapshotDate = :day")
    int deleteBySnapshotDate(@Param("day") LocalDate day);
}
