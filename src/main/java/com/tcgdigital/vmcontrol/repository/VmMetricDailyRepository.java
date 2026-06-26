package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.Optional;

@Repository
public interface VmMetricDailyRepository extends JpaRepository<VmMetricDaily, String> {
    Optional<VmMetricDaily> findByVmVmIdAndBucketDate(String vmId, Date bucketDate);
}
