package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.DiscoveryIgnore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscoveryIgnoreRepository extends JpaRepository<DiscoveryIgnore, String> {

    List<DiscoveryIgnore> findAllByOrderByIgnoredAtDesc();

    Optional<DiscoveryIgnore> findByResourceKindAndAwsAccountRefAndRegionCodeAndResourceId(
            DiscoveryIgnore.Kind kind, String awsAccountRef, String regionCode, String resourceId);
}
