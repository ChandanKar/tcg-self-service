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
}
