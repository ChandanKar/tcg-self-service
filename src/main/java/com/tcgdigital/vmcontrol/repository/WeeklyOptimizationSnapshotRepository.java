package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.WeeklyOptimizationReportType;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeeklyOptimizationSnapshotRepository extends JpaRepository<WeeklyOptimizationSnapshot, String> {

    Optional<WeeklyOptimizationSnapshot> findByEnvironment_EnvironmentIdAndReportTypeAndSnapshotDate(
            String environmentId, WeeklyOptimizationReportType reportType, Date snapshotDate);

    List<WeeklyOptimizationSnapshot> findByReportTypeAndSnapshotDate(
            WeeklyOptimizationReportType reportType, Date snapshotDate);
}
