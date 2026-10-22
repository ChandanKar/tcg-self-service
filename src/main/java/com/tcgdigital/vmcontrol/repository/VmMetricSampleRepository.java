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

    /**
     * The latest sample of each VM, in one query (environment insights, M36; the metrics sync's
     * window). Ties may return two. A join to each VM's MAX(sample_time), which the
     * (vm_id, sample_time, period_seconds) index answers directly: the earlier correlated subquery
     * ran once per sample row and took ~105 s on a table of a few months (a connection held past
     * Hikari's 60 s leak threshold on every metrics run).
     */
    @Query(value = "SELECT s.* FROM vm_metric_sample s JOIN (SELECT vm_id, MAX(sample_time) AS latest " +
           "FROM vm_metric_sample WHERE vm_id IN (:vmIds) GROUP BY vm_id) m " +
           "ON s.vm_id = m.vm_id AND s.sample_time = m.latest", nativeQuery = true)
    List<VmMetricSample> findLatestByVmIds(@Param("vmIds") List<String> vmIds);

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
           "COUNT(s) AS sampleCount, AVG(s.memoryUtilization) AS avgMemory, MAX(s.memoryUtilization) AS maxMemory, " +
           "COUNT(s.memoryUtilization) AS memorySampleCount " +
           "FROM VmMetricSample s WHERE s.sampleTime >= :dayStart AND s.sampleTime < :dayEnd " +
           "GROUP BY s.vm.vmId")
    List<DailyAggregate> aggregateByVmForPeriod(@Param("dayStart") Timestamp dayStart, @Param("dayEnd") Timestamp dayEnd);

    /**
     * Average CPU per VM since a moment (U07-T01: the environment page's "CPU 24 h"), one grouped
     * query for every listed VM; VMs without samples are absent.
     */
    @Query("SELECT s.vm.vmId AS vmId, AVG(s.cpuUtilization) AS avgCpu FROM VmMetricSample s " +
           "WHERE s.vm.vmId IN :vmIds AND s.sampleTime >= :since AND s.cpuUtilization IS NOT NULL " +
           "GROUP BY s.vm.vmId")
    List<VmCpuAverage> avgCpuByVmIdsSince(@Param("vmIds") java.util.Collection<String> vmIds,
                                          @Param("since") Timestamp since);

    /**
     * Seconds each VM spent under the CPU threshold on Saturdays and Sundays since a time (U08-T08),
     * from the samples' periods. MySQL's DAYOFWEEK: 1 is Sunday, 7 is Saturday.
     */
    @Query(value = "SELECT s.vm_id AS vmId, SUM(s.period_seconds) AS seconds FROM vm_metric_sample s " +
           "WHERE s.vm_id IN (:vmIds) AND s.sample_time >= :since AND s.cpu_utilization < :threshold " +
           "AND DAYOFWEEK(s.sample_time) IN (1, 7) GROUP BY s.vm_id", nativeQuery = true)
    List<VmIdleSeconds> weekendIdleSecondsByVmIds(@Param("vmIds") java.util.Collection<String> vmIds,
                                                  @Param("since") Timestamp since,
                                                  @Param("threshold") java.math.BigDecimal threshold);

    interface VmIdleSeconds {
        String getVmId();
        Number getSeconds();
    }

    interface VmCpuAverage {
        String getVmId();
        Double getAvgCpu();
    }

    interface DailyAggregate {
        String getVmId();
        java.math.BigDecimal getAvgCpu();
        java.math.BigDecimal getMaxCpu();
        Long getSumNetworkIn();
        Long getSumNetworkOut();
        Long getSumDiskRead();
        Long getSumDiskWrite();
        Long getSampleCount();
        /** Null when no sample carried memory (no CloudWatch agent); COUNT of the column ignores nulls. */
        java.math.BigDecimal getAvgMemory();
        java.math.BigDecimal getMaxMemory();
        Long getMemorySampleCount();
    }
}
