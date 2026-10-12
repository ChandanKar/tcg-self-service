package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.SpotPriceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

public interface SpotPriceSnapshotRepository extends JpaRepository<SpotPriceSnapshot, String> {

    Optional<SpotPriceSnapshot> findByRegionAndAvailabilityZoneAndInstanceTypeAndCapturedOn(
            String region, String availabilityZone, String instanceType, Date capturedOn);

    /** The latest day with a price for this type in this region, or null (E31-T02). */
    @Query("SELECT MAX(s.capturedOn) FROM SpotPriceSnapshot s WHERE s.region = :region AND s.instanceType = :type")
    Date findLatestCapturedOn(@Param("region") String region, @Param("type") String instanceType);

    List<SpotPriceSnapshot> findByRegionAndInstanceTypeAndCapturedOn(String region, String instanceType, Date capturedOn);
}
