package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An environment's region (U19-T04): its metadata, else its VMs' one region, else unknown; never aws.region. */
class EnvironmentRegionResolverTest {

    private VmRepository vms;
    private EnvironmentRegionResolver resolver;

    @BeforeEach
    void setUp() {
        vms = mock(VmRepository.class);
        resolver = new EnvironmentRegionResolver(vms);
    }

    private static Environment env(String id, String metadata) {
        Environment e = new Environment();
        e.setEnvironmentId(id);
        e.setName(id);
        e.setMetadata(metadata);
        return e;
    }

    private static VmRepository.EnvironmentRegion row(String envId, String region) {
        return new VmRepository.EnvironmentRegion() {
            public String getEnvironmentId() { return envId; }
            public String getRegion() { return region; }
        };
    }

    @Test
    void theMetadataRegionIsReadWhenItIsAString() {
        assertThat(EnvironmentRegionResolver.metadataRegion("{\"region\":\" eu-west-1 \",\"owner\":\"x\"}")).contains("eu-west-1");
        assertThat(EnvironmentRegionResolver.metadataRegion("{\"region\":\"\"}")).isEmpty();
        assertThat(EnvironmentRegionResolver.metadataRegion("{\"region\":null}")).isEmpty();
        assertThat(EnvironmentRegionResolver.metadataRegion("{\"region\":7}")).isEmpty();
        assertThat(EnvironmentRegionResolver.metadataRegion("not json")).isEmpty();
        assertThat(EnvironmentRegionResolver.metadataRegion(null)).isEmpty();
    }

    @Test
    void theMetadataWinsThenTheVmsOneRegionThenNothing() {
        when(vms.findDistinctRegionsGroupedByEnvironment(List.of("a"))).thenReturn(List.of(row("a", "ap-south-1")));
        when(vms.findDistinctRegionsGroupedByEnvironment(List.of("b"))).thenReturn(List.of(row("b", "ap-south-1"), row("b", "us-east-1")));
        when(vms.findDistinctRegionsGroupedByEnvironment(List.of("c"))).thenReturn(List.of());

        assertThat(resolver.regionOf(env("a", "{\"region\":\"me-central-1\"}"))).contains("me-central-1");
        assertThat(resolver.regionOf(env("a", null))).contains("ap-south-1");
        assertThat(resolver.regionOf(env("b", null))).isEqualTo(Optional.empty()); // mixed: not guessed
        assertThat(resolver.regionOf(env("c", "{}"))).isEmpty();
    }

    @Test
    void environmentsWithoutARegionAreFoundInOneQuery() {
        when(vms.findDistinctRegionsGroupedByEnvironment(anyList())).thenReturn(List.of(row("with-vms", "eu-west-1")));

        List<Environment> missing = resolver.withoutRegion(List.of(env("stored", "{\"region\":\"eu-west-1\"}"),
                env("with-vms", null), env("nothing", "{\"owner\":\"x\"}")));

        assertThat(missing).extracting(Environment::getEnvironmentId).containsExactly("nothing");
    }
}
