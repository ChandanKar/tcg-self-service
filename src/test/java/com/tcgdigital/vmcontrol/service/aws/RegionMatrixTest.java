package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.PricingReferenceService;
import com.tcgdigital.vmcontrol.service.settings.SettingsRegistry;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.regions.Region;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The region matrix (U19-T09): every AWS commercial region, opt-in ones included, goes through the
 * app's region code the same way: a valid region code, a regional client in that region (EC2, EKS,
 * CloudWatch, RDS; STS for assumed roles), kept by the scan rules, a price (from the bundled list at
 * least), accepted by the settings, and named in the browser. No network: the clients are only built.
 * GovCloud and China are out of scope (owner decision, 2026-10-22).
 */
class RegionMatrixTest {

    /** Every commercial region as of October 2026. Opt-in regions are marked by AWS, not here: the code treats them alike. */
    static final List<String> COMMERCIAL = List.of("us-east-1", "us-east-2", "us-west-1", "us-west-2", "af-south-1",
            "ap-east-1", "ap-east-2", "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2", "ap-southeast-3",
            "ap-southeast-4", "ap-southeast-5", "ap-southeast-6", "ap-southeast-7", "ap-northeast-1", "ap-northeast-2",
            "ap-northeast-3", "ca-central-1", "ca-west-1", "eu-central-1", "eu-central-2", "eu-west-1", "eu-west-2",
            "eu-west-3", "eu-south-1", "eu-south-2", "eu-north-1", "il-central-1", "me-south-1", "me-central-1",
            "mx-central-1", "sa-east-1");

    private static AwsClientFactory factory;
    private static PricingReferenceService prices;

    static Stream<String> regions() {
        return COMMERCIAL.stream();
    }

    @BeforeAll
    static void setUp() {
        factory = new AwsClientFactory();
        ReflectionTestUtils.setField(factory, "accessKey", "AKIA-matrix");
        ReflectionTestUtils.setField(factory, "secretKey", "secret");
        ReflectionTestUtils.setField(factory, "defaultRegion", "ap-south-1");
        ReflectionTestUtils.setField(factory, "useDefaultChain", false);
        factory.init();
        prices = new PricingReferenceService();
        ReflectionTestUtils.invokeMethod(prices, "loadPricing");
    }

    @AfterAll
    static void tearDown() {
        factory.closeAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("regions")
    void everyRegionIsHandledTheSameWay(String region) {
        assertThat(AwsRegions.isRegionCode(region)).isTrue();
        assertThat(AwsRegions.require(region, "test")).isEqualTo(region);

        // Regional clients go to that region, for the default account and a member.
        Region r = Region.of(region);
        assertThat(factory.ec2(region).serviceClientConfiguration().region()).isEqualTo(r);
        assertThat(factory.eks("member-1", region).serviceClientConfiguration().region()).isEqualTo(r);
        assertThat(factory.cloudWatch(region).serviceClientConfiguration().region()).isEqualTo(r);
        assertThat(factory.rds(null, region).serviceClientConfiguration().region()).isEqualTo(r);
        // STS in that region is a regional endpoint (tokens valid in opt-in regions), never aws-global.
        assertThat(factory.sts(region).serviceClientConfiguration().region()).isEqualTo(r).isNotEqualTo(Region.AWS_GLOBAL);

        // The scan keeps it when the account has it enabled, and leaves it out when excluded or refused.
        assertThat(AwsAccountService.regionsFor(Optional.of(COMMERCIAL), List.of(), List.of(), Set.of(), Set.of(), "ap-south-1"))
                .contains(region);
        assertThat(AwsAccountService.regionsFor(Optional.of(COMMERCIAL), List.of(), List.of(), Set.of(region), Set.of(), "ap-south-1"))
                .doesNotContain(region);
        assertThat(AwsAccountService.regionsFor(Optional.of(COMMERCIAL), List.of(), List.of(), Set.of(), Set.of(region), "ap-south-1"))
                .doesNotContain(region);
        assertThat(CloudTarget.of("acct", region).key()).isEqualTo("acct:" + region);

        // A common type has a price there (exact or from another region, marked approximate).
        PricingReferenceService.PriceLookupResult price = prices.lookupHourlyRate("AWS", "t3.medium", region);
        assertThat(price.priceKnown()).isTrue();
        assertThat(price.hourlyRate().signum()).isPositive();

        // Settings accept it in the exclude list.
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(SettingsService.class, "validate", SettingsRegistry.find("aws.regions.exclude").orElseThrow(), region))
                .doesNotThrowAnyException();
    }

    @Test
    void theBrowserNamesExactlyTheseRegions() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/static/js/config.js"));
        Matcher m = Pattern.compile("\\{ value: '([a-z0-9-]+)', label: '[^']+' \\}").matcher(config);
        Set<String> named = new TreeSet<>();
        while (m.find()) {
            if (AwsRegions.isRegionCode(m.group(1))) named.add(m.group(1));
        }
        assertThat(named).isEqualTo(new TreeSet<>(COMMERCIAL));
    }

    @Test
    void notARegionIsRefusedEverywhere() {
        for (String bad : List.of("mumbai", "us-east", "eu-west-1a", "US-EAST-1 ", "")) {
            assertThat(AwsRegions.isRegionCode(bad)).as(bad).isFalse();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(SettingsService.class, "validate",
                SettingsRegistry.find("aws.regions.exclude").orElseThrow(), "eu-west-1a")).isInstanceOf(ValidationException.class);
    }
}
