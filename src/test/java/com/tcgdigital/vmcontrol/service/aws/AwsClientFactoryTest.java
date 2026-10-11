package com.tcgdigital.vmcontrol.service.aws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Credential mode selection, region fallback, per-region caching and shutdown (E28-T01). No AWS call is made. */
class AwsClientFactoryTest {

    private AwsClientFactory factory;

    private AwsClientFactory factory(String accessKey, String secretKey, boolean defaultChain) {
        factory = new AwsClientFactory();
        ReflectionTestUtils.setField(factory, "accessKey", accessKey);
        ReflectionTestUtils.setField(factory, "secretKey", secretKey);
        ReflectionTestUtils.setField(factory, "defaultRegion", "ap-south-1");
        ReflectionTestUtils.setField(factory, "useDefaultChain", defaultChain);
        factory.init();
        return factory;
    }

    @AfterEach
    void close() {
        if (factory != null) factory.closeAll();
    }

    @Test
    void staticKeysAreUsedWhenBothAreSet() {
        AwsClientFactory f = factory("AKIA-test", "secret", true);
        assertThat(f.isConfigured()).isTrue();
        assertThat(f.mode()).isEqualTo(AwsClientFactory.Mode.STATIC_KEYS);
        assertThat(f.baseCredentials()).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(f.baseCredentials().resolveCredentials().accessKeyId()).isEqualTo("AKIA-test");
    }

    @Test
    void blankKeysWithoutTheChainAreNotConfigured() {
        AwsClientFactory f = factory("", " ", false);
        assertThat(f.isConfigured()).isFalse();
        assertThat(f.mode()).isEqualTo(AwsClientFactory.Mode.NONE);
        assertThatThrownBy(f::baseCredentials).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void blankKeysWithTheChainUseTheDefaultProvider() {
        AwsClientFactory f = factory("", "", true);
        assertThat(f.isConfigured()).isTrue();
        assertThat(f.mode()).isEqualTo(AwsClientFactory.Mode.DEFAULT_CHAIN);
        assertThat(f.baseCredentials()).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void clientsAreCachedPerRegionAndFallBackToTheDefaultRegion() {
        AwsClientFactory f = factory("AKIA-test", "secret", false);
        Ec2Client eu = f.ec2("eu-west-1");
        assertThat(f.ec2("eu-west-1")).isSameAs(eu);
        assertThat(f.ec2("us-east-1")).isNotSameAs(eu);
        assertThat(f.ec2(null)).isSameAs(f.ec2("ap-south-1"));
        assertThat(f.ec2(" ")).isSameAs(f.ec2("ap-south-1"));
        assertThat(f.ec2(null).serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTH_1);
        assertThat(f.costExplorer().serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
        assertThat(f.costExplorer()).isSameAs(f.costExplorer());
    }

    @Test
    void closeAllIsIdempotentAndLaterCallsBuildFreshClients() {
        AwsClientFactory f = factory("AKIA-test", "secret", false);
        Ec2Client before = f.ec2("eu-west-1");
        f.closeAll();
        f.closeAll();
        assertThat(f.ec2("eu-west-1")).isNotSameAs(before);
    }
}
