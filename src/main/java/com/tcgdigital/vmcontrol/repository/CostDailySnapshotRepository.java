package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface CostDailySnapshotRepository extends JpaRepository<CostDailySnapshot, String> {

    Optional<CostDailySnapshot> findByEnvironmentEnvironmentIdAndSnapshotDate(String environmentId, Date snapshotDate);

    /**
     * Guards {@code ActualCostIngestionScheduler} against re-billing the Cost Explorer API for a
     * day whose actuals were already ingested (e.g. after an app restart) — Cost Explorer charges
     * per call, so this check is cheaper than a separate ingestion-watermark table.
     */
    boolean existsBySnapshotDateAndActualCostIsNotNull(Date snapshotDate);

    /**
     * Fleet-wide daily totals since a given date, in one query — feeds the spend trend chart.
     */
    @Query("SELECT c.snapshotDate AS snapshotDate, SUM(c.estimatedCost) AS totalEstimatedCost, " +
           "SUM(c.actualCost) AS totalActualCost " +
           "FROM CostDailySnapshot c WHERE c.snapshotDate >= :sinceDate " +
           "GROUP BY c.snapshotDate ORDER BY c.snapshotDate ASC")
    List<DailyCostTotal> findDailyTotalsSince(@Param("sinceDate") Date sinceDate);

    /**
     * Snapshots since a given date with their environment eagerly fetched, so the per-team
     * trend can read each environment's metadata (for team resolution) without an N+1 lazy-load
     * per snapshot row.
     */
    @Query("SELECT c FROM CostDailySnapshot c JOIN FETCH c.environment WHERE c.snapshotDate >= :sinceDate " +
           "ORDER BY c.snapshotDate ASC")
    List<CostDailySnapshot> findWithEnvironmentSince(@Param("sinceDate") Date sinceDate);

    /**
     * Per-environment totals over a date window — feeds the Weekly Cost Report's week-over-week
     * comparison (called once for "this week", once for "last week").
     */
    @Query("SELECT c.environment.environmentId AS environmentId, " +
           "SUM(c.estimatedCost) AS totalEstimatedCost, SUM(c.actualCost) AS totalActualCost " +
           "FROM CostDailySnapshot c WHERE c.snapshotDate >= :start AND c.snapshotDate < :end " +
           "GROUP BY c.environment.environmentId")
    List<EnvironmentCostTotal> sumByEnvironmentBetween(@Param("start") Date start, @Param("end") Date end);

    interface DailyCostTotal {
        Date getSnapshotDate();
        BigDecimal getTotalEstimatedCost();
        BigDecimal getTotalActualCost();
    }

    interface EnvironmentCostTotal {
        String getEnvironmentId();
        BigDecimal getTotalEstimatedCost();
        BigDecimal getTotalActualCost();
    }

    /** One environment's daily snapshots in a date range, oldest first (owner cost view, E18). */
    List<CostDailySnapshot> findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String environmentId, java.sql.Date from, java.sql.Date to);

    /** Daily estimates of many environments in one query (My Account cost list, E18-T02). */
    @Query("SELECT c.environment.environmentId AS environmentId, c.snapshotDate AS snapshotDate, " +
           "c.estimatedCost AS estimatedCost FROM CostDailySnapshot c " +
           "WHERE c.environment.environmentId IN :ids AND c.snapshotDate BETWEEN :from AND :to")
    List<EnvironmentDailyCost> findDailyByEnvironmentIdsBetween(@Param("ids") List<String> environmentIds,
                                                                @Param("from") java.sql.Date from,
                                                                @Param("to") java.sql.Date to);

    interface EnvironmentDailyCost {
        String getEnvironmentId();
        java.sql.Date getSnapshotDate();
        java.math.BigDecimal getEstimatedCost();
    }
}
