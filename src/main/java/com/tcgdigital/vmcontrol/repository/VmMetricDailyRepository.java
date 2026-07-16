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
     * Average-of-daily-averages and max CPU per VM since a given date, for a batch of VMs in one
     * query — avoids a per-VM query when computing rightsizing candidates for a page of VMs.
     * Note: this averages each day's already-averaged value, not sample-weighted across days
     * (a day with 3 samples counts the same as a day with 288) — an accepted approximation given
     * only daily rollups are queried here, not raw samples.
     */
    @Query("SELECT m.vm.vmId AS vmId, AVG(m.avgCpuUtilization) AS avgCpu, MAX(m.maxCpuUtilization) AS maxCpu " +
           "FROM VmMetricDaily m WHERE m.vm.vmId IN :vmIds AND m.bucketDate >= :sinceDate " +
           "GROUP BY m.vm.vmId")
    List<CpuStats> findCpuStatsSince(@Param("vmIds") List<String> vmIds, @Param("sinceDate") Date sinceDate);

    interface CpuStats {
        String getVmId();
        BigDecimal getAvgCpu();
        BigDecimal getMaxCpu();
    }
}
