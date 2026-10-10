package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rate from the VM's own region is exact; another region's rate is usable but flagged as
 * approximate; an unknown type is unknown (E08-T07).
 */
class PricingReferenceServiceTest {

    private PricingReferenceService pricing;

    @BeforeEach
    void setUp() {
        pricing = new PricingReferenceService();
        pricing.loadPricing();
    }

    @Test
    void theVmsOwnRegionGivesAnExactRate() {
        PricingReferenceService.PriceLookupResult r = pricing.lookupHourlyRate("AWS", "t3.large", "us-east-1");

        assertThat(r.priceKnown()).isTrue();
        assertThat(r.approximate()).isFalse();
    }

    @Test
    void aRegionMissingFromThePricingFileFallsBackToAnotherRegionAsApproximate() {
        PricingReferenceService.PriceLookupResult r = pricing.lookupHourlyRate("AWS", "t3.large", "sa-east-1");

        assertThat(r.priceKnown()).isTrue();
        assertThat(r.hourlyRate()).isNotNull();
        assertThat(r.approximate()).isTrue();
    }

    @Test
    void anUnknownTypeIsUnknown() {
        PricingReferenceService.PriceLookupResult r = pricing.lookupHourlyRate("AWS", "zz9.mega", "us-east-1");

        assertThat(r.priceKnown()).isFalse();
        assertThat(r.approximate()).isFalse();
    }
}
