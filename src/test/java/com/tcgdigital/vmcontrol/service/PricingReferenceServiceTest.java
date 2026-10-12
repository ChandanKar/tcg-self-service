package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rate from the VM's own region is exact; another region's rate is usable but flagged as
 * approximate; an unknown type is unknown (E08-T07). Instance specs and newer-generation and
 * Graviton targets come from the same reference (E30-T02).
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

    @Test
    void specsGiveVcpuMemoryAndArchitecture() {
        PricingReferenceService.InstanceSpec m7g = pricing.getSpec("m7g.large").orElseThrow();
        assertThat(m7g.architecture()).isEqualTo("arm64");
        assertThat(m7g.isArm()).isTrue();
        assertThat(m7g.vcpu()).isEqualTo(2);
        assertThat(m7g.memoryMib()).isEqualTo(8192);
        assertThat(m7g.generation()).isEqualTo(7);

        assertThat(pricing.getSpec("r6i.2xlarge").orElseThrow().memoryMib()).isEqualTo(65536);
        assertThat(pricing.getSpec("c7i.xlarge").orElseThrow().memoryMib()).isEqualTo(8192);
        assertThat(pricing.getSpec("t3.micro").orElseThrow().architecture()).isEqualTo("x86_64");
        assertThat(pricing.getSpec("zz9.mega")).isEmpty();
        assertThat(pricing.getSpec(null)).isEmpty();
    }

    @Test
    void newerGenerationAndGravitonTargetsKeepTheSize() {
        assertThat(pricing.suggestNewerGeneration("AWS", "m5.large", "x86_64")).contains("m7i.large");
        assertThat(pricing.suggestNewerGeneration("AWS", "r6i.2xlarge", "x86_64")).contains("r7i.2xlarge");
        assertThat(pricing.suggestGraviton("AWS", "m5.large")).contains("m7g.large");
        assertThat(pricing.suggestGraviton("AWS", "t3.medium")).contains("t4g.medium");
        // no x86 burstable newer than t3; already-current and unknown types have no target
        assertThat(pricing.suggestNewerGeneration("AWS", "t3.medium", "x86_64")).isEmpty();
        assertThat(pricing.suggestGraviton("AWS", "m7g.large")).isEmpty();
        assertThat(pricing.suggestGraviton("AWS", "zz9.mega")).isEmpty();
        assertThat(pricing.suggestNewerGeneration("AWS", "m5.large", null)).isEmpty();
    }

    @Test
    void everyPricedTypeHasASpecAndEveryUpgradeTargetIsPriced() {
        for (String type : new String[] {"m7i.large", "m7g.4xlarge", "c7g.large", "r7i.xlarge", "t4g.small", "m6a.4xlarge"}) {
            assertThat(pricing.getSpec(type)).as(type).isPresent();
            PricingReferenceService.PriceLookupResult r = pricing.lookupHourlyRate("AWS", type, "us-east-1");
            assertThat(r.priceKnown()).as(type).isTrue();
            assertThat(r.approximate()).as(type).isFalse();
        }
    }

    @Test
    void existingLookupsAreUnchanged() {
        assertThat(pricing.lookupHourlyRate("AWS", "m5.large", "ap-south-1").hourlyRate()).isEqualByComparingTo("0.104");
        assertThat(pricing.lookupHourlyRate("AWS", "t3.large", "eu-west-1").hourlyRate()).isEqualByComparingTo("0.0928");
        assertThat(pricing.suggestSmallerType("AWS", "m5.xlarge")).contains("m5.large");
        assertThat(pricing.suggestSmallerType("AWS", "m7g.xlarge")).contains("m7g.large");
        assertThat(pricing.suggestLargerType("AWS", "t3.medium")).contains("t3.large");
    }

    @Test
    void sameShapeTypesShareVcpuMemoryAndArchitecture() {
        java.util.List<String> types = pricing.sameShapeTypes("m6i.large");

        assertThat(types.get(0)).isEqualTo("m6i.large");
        assertThat(types).contains("m5.large", "m6a.large", "m7i.large").doesNotContain("m7g.large", "c6i.large", "m6i.xlarge");
        assertThat(pricing.sameShapeTypes("zz9.mega")).containsExactly("zz9.mega");
    }

    // --- RDS (E33-T05) ---

    @Test
    void rdsRatesDependOnEngineClassAndDeployment() {
        PricingReferenceService.PriceLookupResult postgres = pricing.lookupRdsHourlyRate("postgres", "db.t4g.medium", "us-east-1", false);
        PricingReferenceService.PriceLookupResult multiAz = pricing.lookupRdsHourlyRate("postgres", "db.t4g.medium", "us-east-1", true);
        PricingReferenceService.PriceLookupResult mariadb = pricing.lookupRdsHourlyRate("mariadb", "db.m5.large", "us-east-1", false);

        assertThat(postgres.priceKnown()).isTrue();
        assertThat(postgres.approximate()).isFalse();
        assertThat(postgres.hourlyRate()).isEqualByComparingTo("0.065");
        assertThat(multiAz.hourlyRate()).isEqualByComparingTo("0.13");
        assertThat(mariadb.hourlyRate()).isEqualByComparingTo("0.171");
    }

    @Test
    void rdsFallbacksAreApproximateAndUnpricedEnginesUnknown() {
        PricingReferenceService.PriceLookupResult otherRegion = pricing.lookupRdsHourlyRate("mysql", "db.t3.medium", "ap-south-1", false);
        PricingReferenceService.PriceLookupResult auroraMultiAz = pricing.lookupRdsHourlyRate("aurora-postgresql", "db.r6g.large", "us-east-1", true);
        PricingReferenceService.PriceLookupResult byClassOnly = pricing.lookupHourlyRate("AWS_RDS", "db.t3.medium", "us-east-1");

        assertThat(otherRegion.approximate()).isTrue();
        assertThat(otherRegion.hourlyRate()).isEqualByComparingTo("0.068");
        assertThat(auroraMultiAz.approximate()).isTrue();
        assertThat(auroraMultiAz.hourlyRate()).isEqualByComparingTo("0.52");
        assertThat(byClassOnly.priceKnown()).isTrue();
        assertThat(byClassOnly.approximate()).isTrue();
        assertThat(pricing.lookupRdsHourlyRate("oracle-ee", "db.m5.large", "us-east-1", false).priceKnown()).isFalse();
        assertThat(pricing.lookupRdsHourlyRate("postgres", "db.x2g.large", "us-east-1", false).priceKnown()).isFalse();
        assertThat(pricing.getRdsStorageGbMonthRate(true)).isEqualByComparingTo("0.230");
    }
}
