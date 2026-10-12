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
import java.util.List;
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
    private final Map<String, InstanceSpec> specsByType = new HashMap<>();
    /** "provider:type" -> architecture -> newer-generation type of the same size (E30-T02). */
    private final Map<String, Map<String, String>> generationUpgrades = new HashMap<>();
    private BigDecimal storageGbMonthRate = BigDecimal.ZERO;
    /** RDS: "engine|class|region|deployment" and "engine|class|deployment" (any region) -> hourly rate (E33-T05). */
    private final Map<String, BigDecimal> rdsRates = new HashMap<>();
    private final Map<String, BigDecimal> rdsRatesAnyRegion = new HashMap<>();
    private BigDecimal rdsStorageGbMonthRate = BigDecimal.ZERO;

    /** EKS control-plane fee per cluster-hour: 0.10 standard, 0.60 on extended support (E26-T03). */
    @org.springframework.beans.factory.annotation.Value("${eks.pricing.control-plane-hourly:0.10}")
    private BigDecimal eksControlPlaneHourlyRate = new BigDecimal("0.10");

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

            for (JsonNode spec : root.path("instanceTypes")) {
                InstanceSpec s = new InstanceSpec(spec.get("instanceType").asText(), spec.path("vcpu").asInt(),
                        spec.path("memoryMib").asInt(), spec.path("architecture").asText("x86_64"),
                        spec.path("generation").asInt());
                specsByType.put(s.instanceType(), s);
            }
            for (Map.Entry<String, JsonNode> field : root.path("generationUpgradeMap").properties()) {
                Map<String, String> byArchitecture = new HashMap<>();
                for (Map.Entry<String, JsonNode> target : field.getValue().properties()) {
                    byArchitecture.put(target.getKey(), target.getValue().asText());
                }
                generationUpgrades.put(field.getKey(), byArchitecture);
            }

            if (root.hasNonNull("rdsStorageGbMonthRate")) {
                rdsStorageGbMonthRate = new BigDecimal(root.get("rdsStorageGbMonthRate").asText());
            }
            for (JsonNode entry : root.path("rdsPricing")) {
                String engine = entry.get("engine").asText();
                String dbClass = entry.get("instanceClass").asText();
                String deployment = entry.path("deployment").asText("SINGLE_AZ");
                BigDecimal hourlyRate = new BigDecimal(entry.get("hourlyRate").asText());
                rdsRates.put(String.join("|", engine, dbClass, entry.get("region").asText(), deployment), hourlyRate);
                rdsRatesAnyRegion.putIfAbsent(String.join("|", engine, dbClass, deployment), hourlyRate);
            }

            long regions = ratesByTypeAndRegion.keySet().stream()
                    .map(k -> k.substring(k.lastIndexOf(':') + 1)).distinct().count();
            log.info("Loaded cost pricing reference: {} instance rates in {} regions, {} instance specs, {} downsize rules, "
                    + "{} upsize rules, {} generation upgrades, storage rate ${}/GB-month",
                    ratesByTypeAndRegion.size(), regions, specsByType.size(), downsizeMap.size(), upsizeMap.size(),
                    generationUpgrades.size(), storageGbMonthRate);
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
        if (com.tcgdigital.vmcontrol.model.CloudProvider.AWS_RDS.name().equals(provider)) {
            // Callers that know only the DB class (idle savings, previews, the ledger) get the
            // PostgreSQL Single-AZ rate, flagged approximate (E33-T05).
            PriceLookupResult rds = lookupRdsHourlyRate("postgres", instanceType, region, false);
            return rds.priceKnown() ? new PriceLookupResult(true, rds.hourlyRate(), true) : rds;
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

    /**
     * An RDS database's hourly rate (E33-T05) by engine, DB class, region and Single- or Multi-AZ:
     * the exact rate, else another region's (approximate), else for Multi-AZ twice the Single-AZ rate
     * (approximate: Aurora has no Multi-AZ price, its replicas are separate instances), else unknown.
     * MariaDB is priced as MySQL. SQL Server and Oracle carry license costs the reference lacks: unknown.
     */
    public PriceLookupResult lookupRdsHourlyRate(String engine, String dbClass, String region, boolean multiAz) {
        String e = rdsEngine(engine);
        if (e == null || dbClass == null) {
            return PriceLookupResult.unknown();
        }
        String deployment = multiAz ? "MULTI_AZ" : "SINGLE_AZ";
        BigDecimal rate = rdsRates.get(String.join("|", e, dbClass, String.valueOf(region), deployment));
        if (rate != null) {
            return new PriceLookupResult(true, rate, false);
        }
        rate = rdsRatesAnyRegion.get(String.join("|", e, dbClass, deployment));
        if (rate != null) {
            return new PriceLookupResult(true, rate, true);
        }
        if (multiAz) {
            PriceLookupResult single = lookupRdsHourlyRate(engine, dbClass, region, false);
            if (single.priceKnown()) {
                return new PriceLookupResult(true, single.hourlyRate().multiply(BigDecimal.valueOf(2)), true);
            }
        }
        return PriceLookupResult.unknown();
    }

    /** RDS storage per GB-month (E33-T05); Multi-AZ keeps a standby copy and is billed twice. */
    public BigDecimal getRdsStorageGbMonthRate(boolean multiAz) {
        return multiAz ? rdsStorageGbMonthRate.multiply(BigDecimal.valueOf(2)) : rdsStorageGbMonthRate;
    }

    private static String rdsEngine(String engine) {
        if (engine == null) return null;
        String e = engine.trim().toLowerCase(java.util.Locale.ROOT);
        if (e.equals("postgres") || e.equals("postgresql")) return "postgres";
        if (e.equals("mysql") || e.equals("mariadb")) return "mysql";
        if (e.equals("aurora-postgresql")) return "aurora-postgresql";
        if (e.equals("aurora-mysql") || e.equals("aurora")) return "aurora-mysql";
        return null;
    }

    public BigDecimal getEksControlPlaneHourlyRate() {
        return eksControlPlaneHourlyRate;
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

    /** vCPU, memory and architecture of an instance type, when the reference has it (E30-T02). */
    public Optional<InstanceSpec> getSpec(String instanceType) {
        return instanceType == null ? Optional.empty() : Optional.ofNullable(specsByType.get(instanceType));
    }

    /**
     * The newer-generation type of the same size on the given architecture (m5.large on x86_64 ->
     * m7i.large, on arm64 -> m7g.large); empty when the type is already current or unknown.
     */
    public Optional<String> suggestNewerGeneration(String provider, String instanceType, String architecture) {
        if (provider == null || instanceType == null || architecture == null) {
            return Optional.empty();
        }
        Map<String, String> targets = generationUpgrades.get(typeKey(provider, instanceType));
        return targets == null ? Optional.empty() : Optional.ofNullable(targets.get(architecture));
    }

    /**
     * Every same-size, same-architecture type of a newer generation in the same class (m, c, r or
     * t), newest generation first (E30-T05): m5.large on x86_64 -> m7i.large, m6i.large, m6a.large.
     */
    public List<String> newerGenerationsOf(String instanceType, String architecture) {
        InstanceSpec current = instanceType == null ? null : specsByType.get(instanceType);
        int dot = instanceType == null ? -1 : instanceType.indexOf('.');
        if (current == null || dot < 1 || architecture == null) {
            return List.of();
        }
        char typeClass = instanceType.charAt(0);
        String size = instanceType.substring(dot);
        return specsByType.values().stream()
                .filter(s -> s.instanceType().charAt(0) == typeClass && s.instanceType().endsWith(size)
                        && s.instanceType().indexOf('.') == s.instanceType().length() - size.length()
                        && architecture.equals(s.architecture()) && s.generation() > current.generation())
                .sorted(java.util.Comparator.comparingInt(InstanceSpec::generation).reversed()
                        .thenComparing(InstanceSpec::instanceType))
                .map(InstanceSpec::instanceType)
                .toList();
    }

    /**
     * The type and every other type with the same vCPU, memory and architecture (E31-T04): the
     * diversified set Spot capacity is asked for. Just the type when its spec is unknown.
     */
    public List<String> sameShapeTypes(String instanceType) {
        InstanceSpec spec = instanceType == null ? null : specsByType.get(instanceType);
        if (spec == null) {
            return instanceType == null ? List.of() : List.of(instanceType);
        }
        List<String> types = new java.util.ArrayList<>();
        types.add(instanceType);
        specsByType.values().stream()
                .filter(s -> !s.instanceType().equals(instanceType) && s.vcpu() == spec.vcpu()
                        && s.memoryMib() == spec.memoryMib() && s.architecture().equals(spec.architecture()))
                .map(InstanceSpec::instanceType).sorted().forEach(types::add);
        return types;
    }

    /** The Graviton (arm64) equivalent of the same size, when there is one. */
    public Optional<String> suggestGraviton(String provider, String instanceType) {
        return suggestNewerGeneration(provider, instanceType, "arm64");
    }

    private static String typeAndRegionKey(String provider, String instanceType, String region) {
        return provider + ":" + instanceType + ":" + region;
    }

    private static String typeKey(String provider, String instanceType) {
        return provider + ":" + instanceType;
    }

    public record InstanceSpec(String instanceType, int vcpu, int memoryMib, String architecture, int generation) {
        public boolean isArm() {
            return "arm64".equals(architecture);
        }
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
