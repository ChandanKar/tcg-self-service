package com.tcgdigital.vmcontrol.service.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.PriceCacheEntry;
import com.tcgdigital.vmcontrol.repository.PriceCacheRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * On-demand prices from the AWS Price List API (U19-T05), so a VM in any commercial region has a
 * price, not only the regions of the bundled list. A lookup never waits for AWS: it answers from
 * memory (loaded from price_cache), and a price not known yet, or over a day old, is fetched in the
 * background and is there for the next estimate. "Not sold in that region" is remembered too (a day).
 * Linux, shared tenancy, no pre-installed software, as the bundled list.
 */
@Service
public class AwsPriceListService {

    private static final Logger log = LoggerFactory.getLogger(AwsPriceListService.class);
    static final String EC2 = "AmazonEC2";
    static final Duration MAX_AGE = Duration.ofDays(1);

    /** A price as known now: the hourly rate (null: not sold there) and when it was read. */
    record Known(BigDecimal rate, Instant fetchedAt) { }

    private final PriceCacheRepository repository;
    private final Supplier<PricingClient> client;
    private final BooleanSupplier configured;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Known> known = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;
    private Clock clock = Clock.systemUTC();

    @Value("${cost.price-list.enabled:true}")
    private boolean enabled = true;

    @Autowired
    public AwsPriceListService(PriceCacheRepository repository, AwsClientFactory clientFactory) {
        this(repository, clientFactory::pricing, clientFactory::isConfigured);
    }

    AwsPriceListService(PriceCacheRepository repository, Supplier<PricingClient> client, BooleanSupplier configured) {
        this.repository = repository;
        this.client = client;
        this.configured = configured;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Tests: run fetches on the caller's thread. */
    void runInline() {
        executor = null;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aws-price-list");
            t.setDaemon(true);
            return t;
        });
        try {
            repository.findAll().forEach(e -> known.put(key(e.getServiceCode(), e.getRegionCode(), e.getPriceKey()),
                    new Known(e.getUnitPrice(), e.getFetchedAt().toInstant())));
            log.info("Price list: {} stored price(s) loaded", known.size());
        } catch (Exception e) {
            log.warn("Price list: stored prices not loaded: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * An EC2 instance type's Linux on-demand hourly rate in a region, when the Price List has given
     * one; empty when not known yet (a fetch is started) or not sold there.
     */
    public Optional<BigDecimal> ec2Hourly(String region, String instanceType) {
        if (!enabled || region == null || region.isBlank() || instanceType == null || instanceType.isBlank()) {
            return Optional.empty();
        }
        String k = key(EC2, region, instanceType);
        Known now = known.get(k);
        if (now == null || now.fetchedAt().plus(MAX_AGE).isBefore(clock.instant())) {
            refreshLater(region, instanceType);
        }
        return now == null ? Optional.empty() : Optional.ofNullable(now.rate());
    }

    private void refreshLater(String region, String instanceType) {
        String k = key(EC2, region, instanceType);
        if (!configured.getAsBoolean() || !inFlight.add(k)) {
            return;
        }
        Runnable job = () -> {
            try {
                fetch(region, instanceType);
            } catch (Exception e) {
                // Unreachable: the bundled list (approximate) stays in use; tried again on a later lookup.
                log.info("Price list: {} in {} not read ({}); the bundled list is used", instanceType, region, e.getMessage());
            } finally {
                inFlight.remove(k);
            }
        };
        if (executor == null) job.run();
        else executor.submit(job);
    }

    /** Reads the price from AWS now and stores it (null when AWS lists no such product there). */
    BigDecimal fetch(String region, String instanceType) {
        GetProductsResponse response = client.get().getProducts(GetProductsRequest.builder()
                .serviceCode(EC2)
                .filters(term("instanceType", instanceType), term("regionCode", region), term("operatingSystem", "Linux"),
                        term("tenancy", "Shared"), term("preInstalledSw", "NA"), term("capacitystatus", "Used"),
                        term("licenseModel", "No License required"))
                .maxResults(10).build());
        BigDecimal rate = null;
        for (String product : response.priceList()) {
            rate = onDemandHourly(product);
            if (rate != null && rate.signum() > 0) break;
        }
        store(region, instanceType, rate);
        return rate;
    }

    /** The first non-zero hourly USD price in a product's on-demand terms; null when there is none. */
    BigDecimal onDemandHourly(String productJson) {
        try {
            JsonNode onDemand = mapper.readTree(productJson).path("terms").path("OnDemand");
            for (JsonNode offer : onDemand) {
                for (JsonNode dimension : offer.path("priceDimensions")) {
                    String unit = dimension.path("unit").asText("");
                    String usd = dimension.path("pricePerUnit").path("USD").asText("");
                    if (!usd.isBlank() && (unit.equalsIgnoreCase("Hrs") || unit.equalsIgnoreCase("Hours"))) {
                        BigDecimal value = new BigDecimal(usd);
                        if (value.signum() > 0) return value;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Price list: a product could not be read: {}", e.getMessage());
        }
        return null;
    }

    private void store(String region, String instanceType, BigDecimal rate) {
        Instant now = clock.instant();
        known.put(key(EC2, region, instanceType), new Known(rate, now));
        try {
            PriceCacheEntry row = repository.findByServiceCodeAndRegionCodeAndPriceKey(EC2, region, instanceType).orElseGet(() -> {
                PriceCacheEntry e = new PriceCacheEntry();
                e.setServiceCode(EC2);
                e.setRegionCode(region);
                e.setPriceKey(instanceType);
                return e;
            });
            row.setUnitPrice(rate);
            row.setUnit(rate == null ? null : "Hrs");
            row.setFetchedAt(Timestamp.from(now));
            repository.save(row);
        } catch (Exception e) {
            log.warn("Price list: {} in {} not stored: {}", instanceType, region, e.getMessage());
        }
    }

    private static Filter term(String field, String value) {
        return Filter.builder().type(FilterType.TERM_MATCH).field(field).value(value).build();
    }

    private static String key(String service, String region, String type) {
        return service + "|" + region + "|" + type;
    }

    /** How many prices are known (System health). */
    public int knownCount() {
        return (int) known.values().stream().filter(k -> k.rate() != null).count();
    }

    List<String> knownKeys() {
        return List.copyOf(known.keySet());
    }
}
