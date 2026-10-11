package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The waste sweep (E22-T01): off means no AWS call; findings are upserted and priced; a volume
 * must be unattached long enough; resolution only follows a successful scan; dismissed stays.
 */
class WasteSweepServiceTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");

    @Autowired private WasteFindingRepository findings;
    @Autowired private WastePricing pricing;
    @Autowired private EnvironmentRepository environments;
    @Autowired private ObjectMapper objectMapper;

    private final AwsWasteScanService scans = mock(AwsWasteScanService.class);
    private WasteSweepService sweep;
    private Environment env;

    @BeforeEach
    void setUp() {
        env = newEnvironment("waste");
        sweep = new WasteSweepService(findings, scans, pricing, environments, objectMapper);
        ReflectionTestUtils.setField(sweep, "enabled", true);
        ReflectionTestUtils.setField(sweep, "regions", "ap-south-1");
        at(NOW);
    }

    private void at(Instant now) {
        ReflectionTestUtils.setField(sweep, "clock", Clock.fixed(now, ZoneOffset.UTC));
    }

    private AwsWasteScanService.ScannedResource volume(String id, int gib, Instant created, String environmentName) {
        return new AwsWasteScanService.ScannedResource(id, "data-" + id, "gp2", gib, created,
                environmentName == null ? Map.of() : Map.of("tcg:environment", environmentName), Map.of());
    }

    private WasteFinding finding(String id) {
        return findings.findByFindingTypeAndRegionAndResourceId(WasteFindingType.UNATTACHED_VOLUME, "ap-south-1", id).orElse(null);
    }

    @Test
    void whenOffNothingIsCalled() {
        ReflectionTestUtils.setField(sweep, "enabled", false);
        assertThat(sweep.sweep().ran()).isFalse();
        verifyNoInteractions(scans);
    }

    @Test
    void anOldUnattachedVolumeIsAnOpenPricedFinding() {
        when(scans.scanUnattachedVolumes("ap-south-1")).thenReturn(List.of(
                volume("vol-old", 100, NOW.minus(Duration.ofDays(40)), env.getName()),
                volume("vol-new", 50, NOW.minus(Duration.ofDays(3)), null)));

        WasteSweepService.SweepResult result = sweep.sweep();

        assertThat(result.found()).containsEntry(WasteFindingType.UNATTACHED_VOLUME, 1);
        WasteFinding old = finding("vol-old");
        assertThat(old.getStatus()).isEqualTo(WasteFindingStatus.OPEN);
        assertThat(old.getEstMonthlyCost()).isEqualByComparingTo("10.00");
        assertThat(old.getEnvironmentId()).isEqualTo(env.getEnvironmentId());
        assertThat(old.getDetails()).contains("\"sizeGib\":100");
        assertThat(finding("vol-new")).isNull(); // unattached for only 3 days at most
    }

    @Test
    void anAttachedVolumeIsResolvedOnlyAfterASuccessfulScan() {
        when(scans.scanUnattachedVolumes("ap-south-1")).thenReturn(List.of(volume("vol-1", 100, NOW.minus(Duration.ofDays(40)), null)));
        sweep.sweep();

        at(NOW.plus(Duration.ofDays(1)));
        when(scans.scanUnattachedVolumes("ap-south-1")).thenThrow(new IllegalStateException("throttled"));
        WasteSweepService.SweepResult failed = sweep.sweep();
        assertThat(failed.failures()).singleElement().satisfies(f -> assertThat(f).contains("throttled"));
        assertThat(finding("vol-1").getStatus()).isEqualTo(WasteFindingStatus.OPEN); // unchanged

        at(NOW.plus(Duration.ofDays(2)));
        org.mockito.Mockito.reset(scans);
        when(scans.scanUnattachedVolumes("ap-south-1")).thenReturn(List.of());
        sweep.sweep();
        assertThat(finding("vol-1").getStatus()).isEqualTo(WasteFindingStatus.RESOLVED);
    }

    @Test
    void aDismissedFindingStaysDismissed() {
        when(scans.scanUnattachedVolumes("ap-south-1")).thenReturn(List.of(volume("vol-d", 20, NOW.minus(Duration.ofDays(60)), null)));
        sweep.sweep();
        WasteFinding f = finding("vol-d");
        f.setStatus(WasteFindingStatus.DISMISSED);
        findings.saveAndFlush(f);

        at(NOW.plus(Duration.ofDays(1)));
        sweep.sweep();
        assertThat(finding("vol-d").getStatus()).isEqualTo(WasteFindingStatus.DISMISSED);
        assertThat(finding("vol-d").getEstMonthlyCost()).isEqualByComparingTo("2.00");
    }

    @Test
    void regionsDefaultToTheAwsRegion() {
        ReflectionTestUtils.setField(sweep, "regions", " eu-west-1 , us-east-1,eu-west-1");
        assertThat(sweep.regions()).containsExactly("eu-west-1", "us-east-1");
        ReflectionTestUtils.setField(sweep, "regions", "");
        assertThat(sweep.regions()).containsExactly("ap-south-1");
    }
}
