package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsAccountRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AwsAccountRegionRepository extends JpaRepository<AwsAccountRegion, String> {

    List<AwsAccountRegion> findByAwsAccountRefOrderByRegionCodeAsc(String awsAccountRef);

    Optional<AwsAccountRegion> findByAwsAccountRefAndRegionCode(String awsAccountRef, String regionCode);
}
