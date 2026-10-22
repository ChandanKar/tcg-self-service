package com.tcgdigital.vmcontrol.service.pricing;

import com.tcgdigital.vmcontrol.model.PriceCacheEntry;
import com.tcgdigital.vmcontrol.repository.PriceCacheRepository;
import com.tcgdigital.vmcontrol.service.PricingReferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Prices for every region (U19-T05): the Price List's Linux on-demand rate, read in the background
 * and kept a day; "not sold there" remembered; no AWS access leaves the bundled list in use.
 */
class AwsPriceListServiceTest {

    /** A trimmed GetProducts entry, as AWS returns it (one JSON document per product). */
    static String product(String usd) {
        return """
                {"product":{"productFamily":"Compute Instance","attributes":{"instanceType":"m7i.large","regionCode":"me-central-1",
                 "operatingSystem":"Linux","tenancy":"Shared"}},
                 "terms":{"OnDemand":{"ABC.JRTCKXETXF":{"priceDimensions":{"ABC.JRTCKXETXF.6YS6EN2CT7":{
                   "unit":"Hrs","pricePerUnit":{"USD":"%s"},"description":"$%s per On Demand Linux m7i.large Instance Hour"}}}}}}
                """.formatted(usd, usd);
    }

    private PriceCacheRepository repository;
    private PricingClient pricing;
    private final AtomicBoolean configured = new AtomicBoolean(true);
    private final List<PriceCacheEntry> saved = new ArrayList<>();
    private AwsPriceListService service;
    private Instant now = Instant.parse("2026-10-22T08:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(PriceCacheRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findByServiceCodeAndRegionCodeAndPriceKey(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        pricing = mock(PricingClient.class);
        service = new AwsPriceListService(repository, () -> pricing, configured::get);
        service.start();
        service.runInline();
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    private void awsSays(String... products) {
        when(pricing.getProducts(any(GetProductsRequest.class))).thenReturn(GetProductsResponse.builder().priceList(products).build());
    }

    @Test
    void aRegionOutsideTheBundledListGetsItsRealRate() {
        awsSays(product("0.1235000000"));

        assertThat(service.ec2Hourly("me-central-1", "m7i.large")).isEmpty(); // not known yet: fetched now, for next time
        assertThat(service.ec2Hourly("me-central-1", "m7i.large")).contains(new BigDecimal("0.1235000000"));
        verify(pricing, times(1)).getProducts(org.mockito.ArgumentMatchers.<GetProductsRequest>argThat(r ->
                r.serviceCode().equals("AmazonEC2") && r.filters().stream().anyMatch(f -> f.field().equals("regionCode") && f.value().equals("me-central-1"))
                        && r.filters().stream().anyMatch(f -> f.field().equals("operatingSystem") && f.value().equals("Linux"))));
        assertThat(saved).singleElement().satisfies(e -> {
            assertThat(e.getRegionCode()).isEqualTo("me-central-1");
            assertThat(e.getPriceKey()).isEqualTo("m7i.large");
            assertThat(e.getUnitPrice()).isEqualByComparingTo("0.1235");
        });
    }

    @Test
    void aKnownPriceIsUsedForADayThenReadAgain() {
        awsSays(product("0.10"));
        service.ec2Hourly("eu-south-2", "m5.large");
        service.setClock(Clock.fixed(now.plus(Duration.ofHours(23)), ZoneOffset.UTC));
        service.ec2Hourly("eu-south-2", "m5.large");
        verify(pricing, times(1)).getProducts(any(GetProductsRequest.class));

        service.setClock(Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC));
        assertThat(service.ec2Hourly("eu-south-2", "m5.large")).contains(new BigDecimal("0.10")); // the old price meanwhile
        verify(pricing, times(2)).getProducts(any(GetProductsRequest.class));
    }

    @Test
    void notSoldThereIsRememberedAndAnswersNothing() {
        awsSays();
        service.ec2Hourly("ap-east-1", "x2iedn.metal");
        assertThat(service.ec2Hourly("ap-east-1", "x2iedn.metal")).isEmpty();
        verify(pricing, times(1)).getProducts(any(GetProductsRequest.class));
        assertThat(saved).singleElement().extracting(PriceCacheEntry::getUnitPrice).isNull();
    }

    @Test
    void unreachableOrWithoutAwsTheBundledListStaysInUse() {
        when(pricing.getProducts(any(GetProductsRequest.class))).thenThrow(SdkClientException.create("timeout"));
        assertThat(service.ec2Hourly("me-central-1", "m7i.large")).isEmpty();
        assertThat(saved).isEmpty();

        configured.set(false);
        reset(pricing);
        assertThat(service.ec2Hourly("il-central-1", "m7i.large")).isEmpty();
        verifyNoInteractions(pricing);
    }

    @Test
    void storedPricesAreUsedAfterARestart() {
        PriceCacheEntry row = new PriceCacheEntry();
        row.setServiceCode(AwsPriceListService.EC2);
        row.setRegionCode("me-central-1");
        row.setPriceKey("m7i.large");
        row.setUnitPrice(new BigDecimal("0.1235"));
        row.setFetchedAt(java.sql.Timestamp.from(now.minus(Duration.ofHours(2))));
        when(repository.findAll()).thenReturn(List.of(row));
        AwsPriceListService restarted = new AwsPriceListService(repository, () -> pricing, configured::get);
        restarted.start();
        restarted.runInline();
        restarted.setClock(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(restarted.ec2Hourly("me-central-1", "m7i.large")).contains(new BigDecimal("0.1235"));
        verifyNoInteractions(pricing);
    }

    @Test
    void onlyAPositiveHourlyUsdPriceCounts() {
        assertThat(service.onDemandHourly(product("0.0000000000"))).isNull();
        assertThat(service.onDemandHourly("{\"terms\":{}}")).isNull();
        assertThat(service.onDemandHourly("not json")).isNull();
        assertThat(service.onDemandHourly(product("0.0416"))).isEqualByComparingTo("0.0416");
    }

    @Test
    void thePriceListComesFirstThenTheBundledRegionThenAnotherRegionMarkedApproximate() {
        PricingReferenceService reference = new PricingReferenceService();
        ReflectionTestUtils.invokeMethod(reference, "loadPricing");
        // Without the Price List: me-central-1 is not in the bundled list, so another region's rate, approximate.
        PricingReferenceService.PriceLookupResult before = reference.lookupHourlyRate("AWS", "t3.medium", "me-central-1");
        assertThat(before.priceKnown()).isTrue();
        assertThat(before.approximate()).isTrue();

        awsSays(product("0.0532"));
        ReflectionTestUtils.invokeMethod(reference, "setPriceList", service);
        reference.lookupHourlyRate("AWS", "t3.medium", "me-central-1");
        PricingReferenceService.PriceLookupResult after = reference.lookupHourlyRate("AWS", "t3.medium", "me-central-1");
        assertThat(after.approximate()).isFalse();
        assertThat(after.hourlyRate()).isEqualByComparingTo("0.0532");
        // EKS node types are EC2 prices too.
        assertThat(reference.lookupHourlyRate("AWS_EKS", "t3.medium", "me-central-1").approximate()).isFalse();
    }
}
