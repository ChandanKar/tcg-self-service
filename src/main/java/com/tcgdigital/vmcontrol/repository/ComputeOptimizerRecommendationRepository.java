package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ComputeOptimizerRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

public interface ComputeOptimizerRecommendationRepository extends JpaRepository<ComputeOptimizerRecommendation, String> {

    List<ComputeOptimizerRecommendation> findByVmIdIn(Collection<String> vmIds);

    List<ComputeOptimizerRecommendation> findByProviderVmIdIn(Collection<String> providerVmIds);

    /** One account's stored recommendations in one region (E30-T04). */
    List<ComputeOptimizerRecommendation> findByRegionAndAwsAccountRef(String region, String awsAccountRef);

    /** Rows no capture has refreshed since the cutoff (instances that went away). */
    @Modifying
    @Query("DELETE FROM ComputeOptimizerRecommendation r WHERE r.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") Timestamp cutoff);
}
