package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the static cost-estimation pricing reference (classpath JSON) once at startup.
 * Lookups never fabricate a number: an unresolvable instance type/region returns
 * {@code priceKnown=false} rather than a guessed rate.
 */
@Service
public class PricingReferenceService {

    private static final Logger log = LoggerFactory.getLogger(PricingReferenceService.class);
    private static final String RESOURCE_PATH = "pricing/instance-pricing.json";

    private final Map<String, BigDecimal> ratesByTypeAndRegion = new HashMap<>();
    private final Map<String, BigDecimal> ratesByTypeAnyRegion = new HashMap<>();
    private final Map<String, String> downsizeMap = new HashMap<>();
    private BigDecimal storageGbMonthRate = BigDecimal.ZERO;

    @PostConstruct
    void loadPricing() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            JsonNode root = mapper.readTree(is);

            if (root.hasNonNull("storageGbMonthRate")) {
                storageGbMonthRate = new BigDecimal(root.get("storageGbMonthRate").asText());
            }

            JsonNode instancePricing = root.path("instancePricing");
            for (JsonNode entry : instancePricing) {
                String provider = entry.get("provider").asText();
                String instanceType = entry.get("instanceType").asText();
                String region = entry.get("region").asText();
                BigDecimal hourlyRate = new BigDecimal(entry.get("hourlyRate").asText());

                ratesByTypeAndRegion.put(typeAndRegionKey(provider, instanceType, region), hourlyRate);
                ratesByTypeAnyRegion.putIfAbsent(typeKey(provider, instanceType), hourlyRate);
            }

            for (Map.Entry<String, JsonNode> field : root.path("downsizeMap").properties()) {
                downsizeMap.put(field.getKey(), field.getValue().asText());
            }

            log.info("Loaded cost pricing reference: {} instance rates, {} downsize rules, storage rate ${}/GB-month",
                    ratesByTypeAndRegion.size(), downsizeMap.size(), storageGbMonthRate);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load pricing reference data from " + RESOURCE_PATH, e);
        }
    }

    /**
     * Fallback tiers: exact provider:instanceType:region, then provider:instanceType (any region),
     * then unresolvable.
     */
    public PriceLookupResult lookupHourlyRate(String provider, String instanceType, String region) {
        if (provider == null || instanceType == null) {
            return PriceLookupResult.unknown();
        }
        BigDecimal rate = ratesByTypeAndRegion.get(typeAndRegionKey(provider, instanceType, region));
        if (rate != null) {
            return new PriceLookupResult(true, rate);
        }
        rate = ratesByTypeAnyRegion.get(typeKey(provider, instanceType));
        if (rate != null) {
            return new PriceLookupResult(true, rate);
        }
        return PriceLookupResult.unknown();
    }

    public BigDecimal getStorageGbMonthRate() {
        return storageGbMonthRate;
    }

    public Optional<String> suggestSmallerType(String provider, String instanceType) {
        if (provider == null || instanceType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(downsizeMap.get(typeKey(provider, instanceType)));
    }

    private static String typeAndRegionKey(String provider, String instanceType, String region) {
        return provider + ":" + instanceType + ":" + region;
    }

    private static String typeKey(String provider, String instanceType) {
        return provider + ":" + instanceType;
    }

    public record PriceLookupResult(boolean priceKnown, BigDecimal hourlyRate) {
        public static PriceLookupResult unknown() {
            return new PriceLookupResult(false, null);
        }
    }
}
