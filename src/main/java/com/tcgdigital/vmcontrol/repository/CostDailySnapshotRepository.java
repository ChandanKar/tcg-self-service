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

    interface DailyCostTotal {
        Date getSnapshotDate();
        BigDecimal getTotalEstimatedCost();
        BigDecimal getTotalActualCost();
    }
}
