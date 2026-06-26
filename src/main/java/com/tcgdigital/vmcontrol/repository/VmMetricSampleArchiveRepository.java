package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricSampleArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VmMetricSampleArchiveRepository extends JpaRepository<VmMetricSampleArchive, String> {
    boolean existsByVmVmIdAndSampleTimeAndPeriodSeconds(String vmId, java.sql.Timestamp sampleTime, Integer periodSeconds);
}
