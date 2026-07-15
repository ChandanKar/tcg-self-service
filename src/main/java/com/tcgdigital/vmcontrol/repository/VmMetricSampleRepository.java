package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface VmMetricSampleRepository extends JpaRepository<VmMetricSample, String> {
    boolean existsByVmVmIdAndSampleTimeAndPeriodSeconds(String vmId, Timestamp sampleTime, Integer periodSeconds);
    List<VmMetricSample> findByVmVmIdAndSampleTimeBetweenOrderBySampleTimeAsc(String vmId, Timestamp start, Timestamp end);
    List<VmMetricSample> findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(List<String> vmIds, Timestamp start, Timestamp end);
    List<VmMetricSample> findByVmVmIdOrderBySampleTimeDesc(String vmId, Pageable pageable);
    Optional<VmMetricSample> findTopByVmVmIdOrderBySampleTimeDesc(String vmId);

    @Query("SELECT s FROM VmMetricSample s WHERE s.sampleTime < :cutoff ORDER BY s.sampleTime ASC")
    List<VmMetricSample> findArchiveBatch(@Param("cutoff") Timestamp cutoff, Pageable pageable);

    /**
     * Per-VM aggregate over a single day's worth of raw samples (bounded by [dayStart, dayEnd)),
     * computed server-side in one GROUP BY rather than pulling every raw sample into the JVM —
     * the query the daily rollup runs once per tracked day, not per VM.
     */
    @Query("SELECT s.vm.vmId AS vmId, AVG(s.cpuUtilization) AS avgCpu, MAX(s.cpuUtilization) AS maxCpu, " +
           "SUM(s.networkInBytes) AS sumNetworkIn, SUM(s.networkOutBytes) AS sumNetworkOut, " +
           "SUM(s.diskReadBytes) AS sumDiskRead, SUM(s.diskWriteBytes) AS sumDiskWrite, " +
           "COUNT(s) AS sampleCount " +
           "FROM VmMetricSample s WHERE s.sampleTime >= :dayStart AND s.sampleTime < :dayEnd " +
           "GROUP BY s.vm.vmId")
    List<DailyAggregate> aggregateByVmForPeriod(@Param("dayStart") Timestamp dayStart, @Param("dayEnd") Timestamp dayEnd);

    interface DailyAggregate {
        String getVmId();
        java.math.BigDecimal getAvgCpu();
        java.math.BigDecimal getMaxCpu();
        Long getSumNetworkIn();
        Long getSumNetworkOut();
        Long getSumDiskRead();
        Long getSumDiskWrite();
        Long getSampleCount();
    }
}
