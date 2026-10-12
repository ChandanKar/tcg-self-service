package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Inventory of RDS databases (E33-T03): class, specs, storage, engine, Multi-AZ; nothing while off. */
@ExtendWith(MockitoExtension.class)
class RdsCloudInventoryProviderServiceTest {

    private static final String REGION = "ap-south-1";

    @Mock private AwsClientFactory factory;
    @Mock private RdsCloudProviderService rdsService;
    @Mock private PricingReferenceService pricing;
    @Mock private RdsClient rds;

    private RdsCloudInventoryProviderService service;

    @BeforeEach
    void setUp() {
        lenient().when(factory.isConfigured()).thenReturn(true);
        lenient().when(rdsService.isEnabled()).thenReturn(true);
        lenient().when(factory.rds(anyString(), eq(REGION))).thenReturn(rds);
        lenient().when(pricing.getSpec("t3.medium"))
                .thenReturn(Optional.of(new PricingReferenceService.InstanceSpec("t3.medium", 2, 4096, "x86_64", 3)));
        lenient().when(pricing.getSpec("r6g.large"))
                .thenReturn(Optional.of(new PricingReferenceService.InstanceSpec("r6g.large", 2, 16384, "arm64", 6)));
        service = new RdsCloudInventoryProviderService(factory, rdsService, pricing);
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
    }

    private static boolean filters(DescribeDbInstancesRequest r, String value) {
        return r.filters() != null && !r.filters().isEmpty() && r.filters().get(0).values().contains(value);
    }

    @Test
    void anInstanceReportsItsClassSpecsStorageEngineAndMultiAz() {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(DescribeDbInstancesResponse.builder()
                .dbInstances(DBInstance.builder().dbInstanceIdentifier("orders").dbInstanceClass("db.t3.medium")
                        .engine("postgres").multiAZ(true).allocatedStorage(100).availabilityZone("ap-south-1a").build()).build());

        Map<String, CloudInventoryProviderService.VmInventoryData> found = service.fetchInventory(List.of("db:orders"), CloudTarget.of(REGION));

        CloudInventoryProviderService.VmInventoryData d = found.get("db:orders");
        assertEquals("db.t3.medium", d.getInstanceType());
        assertEquals(2, d.getVcpuCount());
        assertEquals(4096, d.getMemoryMib());
        assertEquals(100, d.getTotalStorageGib());
        assertEquals("postgres", d.getEngine());
        assertTrue(d.getMultiAz());
        assertEquals("ap-south-1a", d.getAvailabilityZone());
        assertTrue(d.getVolumes().isEmpty());
    }

    @Test
    void anAuroraClusterTakesItsWritersClassAndHasNoAllocatedStorage() {
        when(rds.describeDBClusters(any(DescribeDbClustersRequest.class))).thenReturn(DescribeDbClustersResponse.builder()
                .dbClusters(DBCluster.builder().dbClusterIdentifier("aurora-qa").engine("aurora-postgresql").multiAZ(false)
                        .allocatedStorage(1)
                        .dbClusterMembers(DBClusterMember.builder().dbInstanceIdentifier("aurora-qa-2").isClusterWriter(false).build(),
                                DBClusterMember.builder().dbInstanceIdentifier("aurora-qa-1").isClusterWriter(true).build())
                        .build()).build());
        when(rds.describeDBInstances(argThat((DescribeDbInstancesRequest r) -> filters(r, "aurora-qa-1"))))
                .thenReturn(DescribeDbInstancesResponse.builder().dbInstances(DBInstance.builder().dbInstanceIdentifier("aurora-qa-1")
                        .dbInstanceClass("db.r6g.large").engine("aurora-postgresql").build()).build());

        CloudInventoryProviderService.VmInventoryData d = service.fetchInventory(List.of("cluster:aurora-qa"), CloudTarget.of(REGION))
                .get("cluster:aurora-qa");

        assertEquals("db.r6g.large", d.getInstanceType());
        assertEquals(16384, d.getMemoryMib());
        assertEquals("arm64", d.getArchitecture());
        assertEquals("aurora-postgresql", d.getEngine());
        assertFalse(d.getMultiAz());
        assertEquals(0, d.getTotalStorageGib());
        assertNull(d.getStorageGib());
    }

    @Test
    void switchedOffNothingIsRead() {
        when(rdsService.isEnabled()).thenReturn(false);

        assertFalse(service.isAvailable());
        assertTrue(service.fetchInventory(List.of("db:orders"), CloudTarget.of(REGION)).isEmpty());
        verifyNoInteractions(rds);
    }
}
