package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.WasteAction;
import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.repository.WasteActionRepository;
import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.CreateVolumeRequest;
import software.amazon.awssdk.services.ec2.model.CreateVolumeResponse;
import software.amazon.awssdk.services.ec2.model.DeleteVolumeRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.SnapshotState;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeState;
import software.amazon.awssdk.services.ec2.model.VolumeType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Waste fix actions (E22-T04): off by default, never approved by the proposer, a volume is
 * snapshotted (and the snapshot completes) before it is deleted, a volume attached meanwhile is
 * left alone, and a rollback recreates the volume in its AZ with its type and tags.
 */
class WasteActionServiceTest extends AbstractIntegrationTest {

    @Autowired private WasteActionRepository actions;
    @Autowired private WasteFindingRepository findings;
    @Autowired private AuditService auditService;
    @Autowired private AfterCommit afterCommit;
    @Autowired private ObjectMapper objectMapper;

    private final Ec2Client ec2 = mock(Ec2Client.class);
    private WasteActionService service;
    private String findingId;

    @BeforeEach
    void setUp() {
        AwsWasteScanService aws = new AwsWasteScanService(region -> ec2);
        aws.setSnapshotPolling(1, Duration.ofSeconds(5));
        service = new WasteActionService(actions, findings, aws, auditService, afterCommit, objectMapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "executor", (Executor) Runnable::run);

        WasteFinding f = new WasteFinding();
        f.setFindingId(UUID.randomUUID().toString());
        f.setFindingType(WasteFindingType.UNATTACHED_VOLUME);
        f.setRegion("ap-south-1");
        f.setResourceId("vol-1");
        f.setEstMonthlyCost(new BigDecimal("10.00"));
        f.setFirstSeenAt(new Timestamp(System.currentTimeMillis()));
        f.setLastSeenAt(new Timestamp(System.currentTimeMillis()));
        findingId = findings.saveAndFlush(f).getFindingId();
    }

    private void volumeIs(VolumeState state) {
        when(ec2.describeVolumes(any(DescribeVolumesRequest.class))).thenReturn(DescribeVolumesResponse.builder().volumes(
                Volume.builder().volumeId("vol-1").state(state).availabilityZone("ap-south-1a").volumeType(VolumeType.GP3)
                        .size(100).iops(3000).throughput(125).tags(Tag.builder().key("Name").value("old-data").build()).build()).build());
        when(ec2.createSnapshot(any(CreateSnapshotRequest.class))).thenReturn(CreateSnapshotResponse.builder().snapshotId("snap-1").build());
        when(ec2.describeSnapshots(any(DescribeSnapshotsRequest.class)))
                .thenReturn(DescribeSnapshotsResponse.builder().snapshots(Snapshot.builder().snapshotId("snap-1").state(SnapshotState.PENDING).build()).build())
                .thenReturn(DescribeSnapshotsResponse.builder().snapshots(Snapshot.builder().snapshotId("snap-1").state(SnapshotState.COMPLETED).build()).build());
    }

    private WasteFinding finding() {
        return findings.findById(findingId).orElseThrow();
    }

    @Test
    void withActionsOffNothingIsStored() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThatThrownBy(() -> service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Waste fix actions are turned off: set waste.actions.enabled");
        assertThat(actions.count()).isZero();
    }

    @Test
    void anotherAdminApprovesAndTheSnapshotCompletesBeforeTheDelete() {
        volumeIs(VolumeState.AVAILABLE);
        String actionId = service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a").actionId();
        assertThat(finding().getStatus()).isEqualTo(WasteFindingStatus.ACTION_PENDING);

        assertThatThrownBy(() -> service.approve(actionId, "admin-a"))
                .hasMessage("You proposed this action; another admin must approve it");
        service.approve(actionId, "admin-b");

        InOrder order = inOrder(ec2);
        order.verify(ec2).describeVolumes(any(DescribeVolumesRequest.class));
        ArgumentCaptor<CreateSnapshotRequest> snapshot = ArgumentCaptor.forClass(CreateSnapshotRequest.class);
        order.verify(ec2).createSnapshot(snapshot.capture());
        order.verify(ec2, org.mockito.Mockito.times(2)).describeSnapshots(any(DescribeSnapshotsRequest.class));
        order.verify(ec2).deleteVolume(any(DeleteVolumeRequest.class));
        assertThat(snapshot.getValue().tagSpecifications().get(0).tags())
                .extracting(Tag::key).contains("Name", "tcg:waste-action", "tcg:source-volume");

        WasteAction done = actions.findById(actionId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(WasteAction.Status.EXECUTED);
        assertThat(done.getRollbackData()).contains("\"snapshotId\":\"snap-1\"").contains("\"availabilityZone\":\"ap-south-1a\"");
        assertThat(finding().getStatus()).isEqualTo(WasteFindingStatus.RESOLVED);
        assertThatThrownBy(() -> service.approve(actionId, "admin-c")).hasMessage("This action was already executed");
    }

    @Test
    void aVolumeAttachedMeanwhileIsNotDeleted() {
        volumeIs(VolumeState.IN_USE);
        String actionId = service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a").actionId();

        service.approve(actionId, "admin-b");

        verify(ec2, never()).createSnapshot(any(CreateSnapshotRequest.class));
        verify(ec2, never()).deleteVolume(any(DeleteVolumeRequest.class));
        WasteAction failed = actions.findById(actionId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WasteAction.Status.FAILED);
        assertThat(failed.getErrorMessage()).contains("no longer unattached");
        assertThat(finding().getStatus()).isEqualTo(WasteFindingStatus.OPEN);
    }

    @Test
    void aRollbackRecreatesTheVolumeFromTheSnapshot() {
        volumeIs(VolumeState.AVAILABLE);
        when(ec2.createVolume(any(CreateVolumeRequest.class))).thenReturn(CreateVolumeResponse.builder().volumeId("vol-restored").build());
        String actionId = service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a").actionId();
        service.approve(actionId, "admin-b");

        assertThat(service.rollback(actionId, "admin-a").status()).isEqualTo(WasteAction.Status.ROLLED_BACK);

        ArgumentCaptor<CreateVolumeRequest> created = ArgumentCaptor.forClass(CreateVolumeRequest.class);
        verify(ec2).createVolume(created.capture());
        assertThat(created.getValue().snapshotId()).isEqualTo("snap-1");
        assertThat(created.getValue().availabilityZone()).isEqualTo("ap-south-1a");
        assertThat(created.getValue().volumeTypeAsString()).isEqualTo("gp3");
        assertThat(created.getValue().size()).isEqualTo(100);
        assertThat(created.getValue().iops()).isEqualTo(3000);
        assertThat(created.getValue().tagSpecifications().get(0).tags()).extracting(Tag::value).contains("old-data");
        assertThat(actions.findById(actionId).orElseThrow().getRollbackData()).contains("vol-restored");
    }

    @Test
    void aRejectedProposalReopensTheFindingAndTheWrongActionIsRefused() {
        assertThatThrownBy(() -> service.propose(findingId, WasteAction.Type.RELEASE_IP, "admin-a"))
                .hasMessage("RELEASE_IP is not available for a UNATTACHED_VOLUME finding");
        String actionId = service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a").actionId();
        assertThatThrownBy(() -> service.propose(findingId, WasteAction.Type.DELETE_VOLUME, "admin-a"))
                .hasMessage("Only an open finding can get a fix action");

        service.reject(actionId, "admin-b");

        assertThat(finding().getStatus()).isEqualTo(WasteFindingStatus.OPEN);
        assertThat(actions.findById(actionId).orElseThrow().getStatus()).isEqualTo(WasteAction.Status.REJECTED);
        verify(ec2, never()).deleteVolume(any(DeleteVolumeRequest.class));
    }
}
