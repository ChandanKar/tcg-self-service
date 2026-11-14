package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface VmMetricDailyRepository extends JpaRepository<VmMetricDaily, String> {
    Optional<VmMetricDaily> findByVmVmIdAndBucketDate(String vmId, Date bucketDate);

    /**
     * Bulk variant of {@link #findByVmVmIdAndBucketDate} for a whole day at once — lets the
     * rollup job resolve every VM's existing row for a date with one query instead of one per VM.
     */
    List<VmMetricDaily> findByBucketDate(Date bucketDate);

    /**
     * Average-of-daily-averages and max CPU and memory per VM since a given date, for a batch of VMs in one
     * query — avoids a per-VM query when computing rightsizing candidates for a page of VMs.
     * Note: this averages each day's already-averaged value, not sample-weighted across days
     * (a day with 3 samples counts the same as a day with 288) — an accepted approximation given
     * only daily rollups are queried here, not raw samples. Memory is null for VMs without the
     * CloudWatch agent (AVG/MAX ignore the null days). U26-T05: also the highest CloudWatch Maximum
     * of CPU and how many days with CPU data have no Maximum (collected before it was kept).
     */
    @Query("SELECT m.vm.vmId AS vmId, AVG(m.avgCpuUtilization) AS avgCpu, MAX(m.maxCpuUtilization) AS maxCpu, " +
           "AVG(m.avgMemoryUtilization) AS avgMemory, MAX(m.maxMemoryUtilization) AS maxMemory, " +
           "MAX(m.maxCpuMaximum) AS maxCpuMaximum, " +
           "SUM(CASE WHEN m.maxCpuUtilization IS NOT NULL AND m.maxCpuMaximum IS NULL THEN 1 ELSE 0 END) AS cpuDaysWithoutMaximum " +
           "FROM VmMetricDaily m WHERE m.vm.vmId IN :vmIds AND m.bucketDate >= :sinceDate " +
           "GROUP BY m.vm.vmId")
    List<UtilizationStats> findUtilizationStatsSince(@Param("vmIds") List<String> vmIds, @Param("sinceDate") Date sinceDate);

    /**
     * Individual per-day rows (not aggregated) for a batch of VMs since a given date, newest
     * first per VM — used by the scale-down rightsizing rule, which needs to check that the
     * most recent N days are *each* below threshold, not just that the window's average is.
     */
    List<VmMetricDaily> findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(
            List<String> vmIds, Date sinceDate);

    interface UtilizationStats {
        String getVmId();
        BigDecimal getAvgCpu();
        BigDecimal getMaxCpu();
        BigDecimal getAvgMemory();
        BigDecimal getMaxMemory();
        /** The highest CloudWatch Maximum of CPU (U26-T05); null when no day has one. */
        BigDecimal getMaxCpuMaximum();
        /** Days with CPU data but no Maximum; the peak on those days is only the busiest 5-minute average. */
        Number getCpuDaysWithoutMaximum();
    }
}
