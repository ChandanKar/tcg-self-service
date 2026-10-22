package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.PriceCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceCacheRepository extends JpaRepository<PriceCacheEntry, String> {

    Optional<PriceCacheEntry> findByServiceCodeAndRegionCodeAndPriceKey(String serviceCode, String regionCode, String priceKey);
}
