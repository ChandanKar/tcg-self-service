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
    private final Map<String, String> upsizeMap = new HashMap<>();
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

            // Upsize map is derived from downsizeMap rather than a second maintained list — for
            // each "provider:largerType -> smallerType" downsize rule, the reverse
            // "provider:smallerType -> largerType" is a reasonable scale-up suggestion. Where
            // multiple downsize entries invert to the same key, first-seen wins (accepted
            // approximation, same spirit as the CpuStats query's documented averaging shortcut).
            for (Map.Entry<String, String> entry : downsizeMap.entrySet()) {
                String key = entry.getKey(); // e.g. "AWS:m5.xlarge"
                String smallerType = entry.getValue(); // e.g. "m5.large"
                int separatorIndex = key.indexOf(':');
                if (separatorIndex < 0) {
                    continue;
                }
                String provider = key.substring(0, separatorIndex);
                String largerType = key.substring(separatorIndex + 1);
                upsizeMap.putIfAbsent(typeKey(provider, smallerType), largerType);
            }

            log.info("Loaded cost pricing reference: {} instance rates, {} downsize rules, {} upsize rules, storage rate ${}/GB-month",
                    ratesByTypeAndRegion.size(), downsizeMap.size(), upsizeMap.size(), storageGbMonthRate);
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
            return new PriceLookupResult(true, rate, false);
        }
        // Another region's rate: usable, but flagged as approximate (E08-T07).
        rate = ratesByTypeAnyRegion.get(typeKey(provider, instanceType));
        if (rate != null) {
            return new PriceLookupResult(true, rate, true);
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

    public Optional<String> suggestLargerType(String provider, String instanceType) {
        if (provider == null || instanceType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(upsizeMap.get(typeKey(provider, instanceType)));
    }

    private static String typeAndRegionKey(String provider, String instanceType, String region) {
        return provider + ":" + instanceType + ":" + region;
    }

    private static String typeKey(String provider, String instanceType) {
        return provider + ":" + instanceType;
    }

    /**
     * {@code approximate} is true when the rate came from another region because the VM's own
     * region has none in the pricing file (E08-T07).
     */
    public record PriceLookupResult(boolean priceKnown, BigDecimal hourlyRate, boolean approximate) {
        public PriceLookupResult(boolean priceKnown, BigDecimal hourlyRate) {
            this(priceKnown, hourlyRate, false);
        }

        public static PriceLookupResult unknown() {
            return new PriceLookupResult(false, null, false);
        }
    }
}
