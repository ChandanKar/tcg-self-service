package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Waste scans against a mocked EC2 client (E22): filters, pagination, and failures that throw. */
class AwsWasteScanServiceTest {

    private final Ec2Client ec2 = mock(Ec2Client.class);
    private final AwsWasteScanService scans = new AwsWasteScanService(region -> ec2);

    @Test
    void unattachedVolumesAreFilteredOnAvailableAndPaged() {
        Volume v1 = Volume.builder().volumeId("vol-1").size(100).volumeType(VolumeType.GP2)
                .createTime(Instant.parse("2026-08-01T00:00:00Z")).tags(Tag.builder().key("Name").value("old-data").build()).build();
        Volume v2 = Volume.builder().volumeId("vol-2").size(8).volumeType(VolumeType.GP3).build();
        when(ec2.describeVolumes(any(DescribeVolumesRequest.class)))
                .thenReturn(DescribeVolumesResponse.builder().volumes(v1).nextToken("t2").build())
                .thenReturn(DescribeVolumesResponse.builder().volumes(v2).build());

        List<AwsWasteScanService.ScannedResource> found = scans.scanUnattachedVolumes("ap-south-1");

        assertThat(found).extracting(AwsWasteScanService.ScannedResource::resourceId).containsExactly("vol-1", "vol-2");
        assertThat(found.get(0).name()).isEqualTo("old-data");
        assertThat(found.get(0).sizeGib()).isEqualTo(100);
        assertThat(found.get(0).volumeType()).isEqualTo("gp2");
        ArgumentCaptor<DescribeVolumesRequest> sent = ArgumentCaptor.forClass(DescribeVolumesRequest.class);
        verify(ec2, times(2)).describeVolumes(sent.capture());
        assertThat(sent.getAllValues().get(0).filters()).singleElement().satisfies(f -> {
            assertThat(f.name()).isEqualTo("status");
            assertThat(f.values()).containsExactly("available");
        });
        assertThat(sent.getAllValues().get(1).nextToken()).isEqualTo("t2");
    }

    @Test
    void aFailedCallThrowsInsteadOfReturningNothing() {
        when(ec2.describeVolumes(any(DescribeVolumesRequest.class))).thenThrow(new RuntimeException("UnauthorizedOperation"));
        assertThatThrownBy(() -> scans.scanUnattachedVolumes("ap-south-1")).hasMessageContaining("UnauthorizedOperation");
    }

    @Test
    void onlyUnassociatedAddressesAreWaste() {
        when(ec2.describeAddresses(any(software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeAddressesResponse.builder().addresses(
                        software.amazon.awssdk.services.ec2.model.Address.builder().allocationId("eipalloc-free").publicIp("1.1.1.1").build(),
                        software.amazon.awssdk.services.ec2.model.Address.builder().allocationId("eipalloc-used").publicIp("2.2.2.2")
                                .associationId("eipassoc-1").build()).build());

        assertThat(scans.scanUnassociatedAddresses("ap-south-1")).extracting(AwsWasteScanService.ScannedResource::resourceId)
                .containsExactly("eipalloc-free");
    }

    @Test
    void theStopTimeIsReadFromTheTransitionReason() {
        assertThat(AwsWasteScanService.stoppedAt("User initiated (2026-08-01 10:15:00 GMT)"))
                .isEqualTo(Instant.parse("2026-08-01T10:15:00Z"));
        assertThat(AwsWasteScanService.stoppedAt("Server.ScheduledStop: Stopped due to event")).isNull();
        assertThat(AwsWasteScanService.stoppedAt(null)).isNull();
    }

    @Test
    void snapshotsAnAmiUsesAreNotOld() {
        when(ec2.describeImages(any(software.amazon.awssdk.services.ec2.model.DescribeImagesRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeImagesResponse.builder().images(
                        software.amazon.awssdk.services.ec2.model.Image.builder().imageId("ami-1").blockDeviceMappings(
                                software.amazon.awssdk.services.ec2.model.BlockDeviceMapping.builder().ebs(
                                        software.amazon.awssdk.services.ec2.model.EbsBlockDevice.builder().snapshotId("snap-ami").build()).build())
                                .build()).build());
        Instant old = Instant.parse("2026-05-01T00:00:00Z");
        when(ec2.describeSnapshots(any(software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse.builder().snapshots(
                        software.amazon.awssdk.services.ec2.model.Snapshot.builder().snapshotId("snap-ami").startTime(old).volumeSize(8).build(),
                        software.amazon.awssdk.services.ec2.model.Snapshot.builder().snapshotId("snap-old").startTime(old).volumeSize(8).build(),
                        software.amazon.awssdk.services.ec2.model.Snapshot.builder().snapshotId("snap-new")
                                .startTime(Instant.parse("2026-10-01T00:00:00Z")).volumeSize(8).build()).build());

        assertThat(scans.scanOldSnapshots("ap-south-1", Instant.parse("2026-07-01T00:00:00Z")))
                .extracting(AwsWasteScanService.ScannedResource::resourceId).containsExactly("snap-old");
    }

    @Test
    void stoppedInstancesAreFilteredAndCarryTheirVolumes() {
        when(ec2.describeInstances(any(software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse.builder().reservations(
                        software.amazon.awssdk.services.ec2.model.Reservation.builder().instances(
                                software.amazon.awssdk.services.ec2.model.Instance.builder().instanceId("i-1")
                                        .stateTransitionReason("User initiated (2026-08-01 10:15:00 GMT)")
                                        .blockDeviceMappings(software.amazon.awssdk.services.ec2.model.InstanceBlockDeviceMapping.builder()
                                                .ebs(software.amazon.awssdk.services.ec2.model.EbsInstanceBlockDevice.builder().volumeId("vol-r").build()).build())
                                        .build()).build()).build());

        List<AwsWasteScanService.ScannedResource> found = scans.scanStoppedInstances("ap-south-1");

        assertThat(found).singleElement().satisfies(i -> assertThat(i.facts().get("volumeIds")).isEqualTo(List.of("vol-r")));
        ArgumentCaptor<software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest> sent =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest.class);
        verify(ec2).describeInstances(sent.capture());
        assertThat(sent.getValue().filters().get(0).values()).containsExactly("stopped");
    }
}
