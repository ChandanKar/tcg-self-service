package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricHourly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public interface VmMetricHourlyRepository extends JpaRepository<VmMetricHourly, String> {
    Optional<VmMetricHourly> findByVmVmIdAndBucketStart(String vmId, Timestamp bucketStart);
}
