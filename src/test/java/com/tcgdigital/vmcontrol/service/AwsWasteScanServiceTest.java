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
}
