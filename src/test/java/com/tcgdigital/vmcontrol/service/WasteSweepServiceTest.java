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
    @Autowired private com.tcgdigital.vmcontrol.repository.VmRepository vms;
    @Autowired private com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository history;
    @Autowired private AuditService auditService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final AwsWasteScanService scans = mock(AwsWasteScanService.class);
    private WasteSweepService sweep;
    private Environment env;

    @BeforeEach
    void setUp() {
        env = newEnvironment("waste");
        sweep = new WasteSweepService(findings, scans, pricing, environments, objectMapper, vms, history, auditService);
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

    // ------------------------------------------------------------------ other sweeps (E22-T02)

    private WasteFinding finding(WasteFindingType type, String id) {
        return findings.findByFindingTypeAndRegionAndResourceId(type, "ap-south-1", id).orElse(null);
    }

    @Test
    void anUnassociatedIpAndAGp2VolumeArePriced() {
        when(scans.scanUnassociatedAddresses("ap-south-1")).thenReturn(List.of(new AwsWasteScanService.ScannedResource(
                "eipalloc-1", null, null, 0, null, Map.of(), Map.of("publicIp", "13.0.0.1"))));
        when(scans.scanGp2Volumes("ap-south-1")).thenReturn(List.of(volume("vol-gp2", 500, NOW.minus(Duration.ofDays(400)), null)));

        sweep.sweep();

        assertThat(finding(WasteFindingType.UNASSOCIATED_IP, "eipalloc-1").getEstMonthlyCost()).isEqualByComparingTo("3.65");
        WasteFinding gp2 = finding(WasteFindingType.GP2_VOLUME, "vol-gp2");
        assertThat(gp2.getEstMonthlyCost()).isEqualByComparingTo("10.00"); // 500 x (0.10 - 0.08)
        assertThat(gp2.getDetails()).contains("\"currentMonthlyCost\":50.00");
    }

    @Test
    void aRegisteredVmStoppedFortyFiveDaysAgoIsLinked() {
        com.tcgdigital.vmcontrol.model.Vm vm = newVm(newGroup(env, "app"), "app-1", com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
        jdbcTemplate.update("INSERT INTO vm_state_history (history_id, vm_id, previous_status, new_status, change_source, created_at) "
                + "VALUES (?, ?, 'RUNNING', 'STOPPED', 'user_action', ?)", java.util.UUID.randomUUID().toString(), vm.getVmId(),
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(45))));
        AwsWasteScanService.ScannedResource registered = new AwsWasteScanService.ScannedResource(vm.getProviderVmId(), "x", null, 0, null,
                Map.of(), Map.of("volumeIds", List.of("vol-root"), "stateTransitionReason", "User initiated (2026-10-10 00:00:00 GMT)"));
        AwsWasteScanService.ScannedResource recent = new AwsWasteScanService.ScannedResource("i-recent", "y", null, 0, null,
                Map.of(), Map.of("volumeIds", List.of(), "stateTransitionReason", "User initiated (2026-10-01 00:00:00 GMT)"));
        AwsWasteScanService.ScannedResource unregistered = new AwsWasteScanService.ScannedResource("i-old", "z", null, 0, null,
                Map.of(), Map.of("volumeIds", List.of(), "publicIp", "13.0.0.2", "stateTransitionReason", "User initiated (2026-07-01 00:00:00 GMT)"));
        when(scans.scanStoppedInstances("ap-south-1")).thenReturn(List.of(registered, recent, unregistered));
        when(scans.volumesById(org.mockito.ArgumentMatchers.eq("ap-south-1"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of("vol-root", volume("vol-root", 30, null, null)));

        sweep.sweep();

        WasteFinding stopped = finding(WasteFindingType.LONG_STOPPED_VM, vm.getProviderVmId());
        assertThat(stopped.getVmId()).isEqualTo(vm.getVmId());
        assertThat(stopped.getEnvironmentId()).isEqualTo(env.getEnvironmentId());
        assertThat(stopped.getEstMonthlyCost()).isEqualByComparingTo("3.00"); // 30 GiB gp2
        assertThat(stopped.getEligibleSince().toInstant()).isEqualTo(NOW.minus(Duration.ofDays(45))); // history beats the reason
        assertThat(finding(WasteFindingType.LONG_STOPPED_VM, "i-recent")).isNull();
        assertThat(finding(WasteFindingType.LONG_STOPPED_VM, "i-old").getEstMonthlyCost()).isEqualByComparingTo("3.65"); // its EIP
    }

    @Test
    void oldSnapshotsArePricedAsAnUpperBoundAndOneFailingScanDoesNotStopTheOthers() {
        when(scans.scanUnassociatedAddresses("ap-south-1")).thenThrow(new IllegalStateException("AccessDenied"));
        when(scans.scanOldSnapshots(org.mockito.ArgumentMatchers.eq("ap-south-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new AwsWasteScanService.ScannedResource("snap-1", null, null, 200,
                        NOW.minus(Duration.ofDays(120)), Map.of(), Map.of("volumeId", "vol-x"))));

        WasteSweepService.SweepResult result = sweep.sweep();

        assertThat(result.failures()).singleElement().satisfies(f -> assertThat(f).startsWith("UNASSOCIATED_IP"));
        WasteFinding snap = finding(WasteFindingType.OLD_SNAPSHOT, "snap-1");
        assertThat(snap.getEstMonthlyCost()).isEqualByComparingTo("10.00");
        assertThat(snap.getDetails()).contains("\"costIsUpperBound\":true");
        org.mockito.Mockito.verify(scans).scanOldSnapshots("ap-south-1", NOW.minus(Duration.ofDays(90)));
    }
}
