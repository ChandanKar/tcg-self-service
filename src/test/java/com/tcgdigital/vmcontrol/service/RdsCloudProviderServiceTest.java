package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.CloudProviderService.VmOperationResult;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** RDS as a cloud provider (E33-T01): status mapping, the API per kind, refusals, time-outs, the switch. */
@ExtendWith(MockitoExtension.class)
class RdsCloudProviderServiceTest {

    private static final String REGION = "ap-south-1";
    private static final CloudTarget TARGET = CloudTarget.of(REGION);

    @Mock private RdsClient rds;
    @Mock private AwsClientFactory factory;

    private RdsCloudProviderService service;

    @BeforeEach
    void setUp() {
        lenient().when(factory.isConfigured()).thenReturn(true);
        lenient().when(factory.rds(anyString(), eq(REGION))).thenReturn(rds);
        service = new RdsCloudProviderService(factory, Runnable::run);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 0L);
        ReflectionTestUtils.setField(service, "operationTimeoutMs", 300L);
    }

    private static DescribeDbInstancesResponse instance(String status) {
        return instance(DBInstance.builder().dbInstanceIdentifier("orders").engine("postgres").dbInstanceStatus(status).build());
    }

    private static DescribeDbInstancesResponse instance(DBInstance db) {
        return DescribeDbInstancesResponse.builder().dbInstances(db).build();
    }

    private static DescribeDbClustersResponse cluster(String status) {
        return DescribeDbClustersResponse.builder().dbClusters(DBCluster.builder()
                .dbClusterIdentifier("aurora-qa").engine("aurora-postgresql").engineMode("provisioned").status(status).build()).build();
    }

    @Test
    void statusesMapToVmStates() {
        Map<String, VmStatus> expected = Map.ofEntries(
                Map.entry("available", VmStatus.RUNNING), Map.entry("stopped", VmStatus.STOPPED),
                Map.entry("starting", VmStatus.STARTING), Map.entry("stopping", VmStatus.STOPPING),
                Map.entry("deleting", VmStatus.TERMINATED), Map.entry("backing-up", VmStatus.RUNNING),
                Map.entry("modifying", VmStatus.RUNNING), Map.entry("upgrading", VmStatus.RUNNING),
                Map.entry("maintenance", VmStatus.RUNNING), Map.entry("configuring-enhanced-monitoring", VmStatus.RUNNING),
                Map.entry("failed", VmStatus.ERROR), Map.entry("inaccessible-encryption-credentials", VmStatus.ERROR),
                Map.entry("something-new", VmStatus.UNKNOWN));
        expected.forEach((status, vm) -> assertEquals(vm, RdsCloudProviderService.mapStatus(status), status));
        assertEquals(VmStatus.UNKNOWN, RdsCloudProviderService.mapStatus(null));
    }

    @Test
    void providerVmIdsAreParsedByPrefix() {
        assertEquals(new RdsCloudProviderService.RdsId(false, "orders"), RdsCloudProviderService.parse("db:orders"));
        assertEquals(new RdsCloudProviderService.RdsId(true, "aurora-qa"), RdsCloudProviderService.parse("cluster:aurora-qa"));
        assertNull(RdsCloudProviderService.parse("orders"));
        assertNull(RdsCloudProviderService.parse("db:"));
        assertEquals(CloudProvider.AWS_RDS, service.getProvider());
        assertEquals("orders", service.getVmName("db:orders", TARGET));
    }

    @Test
    void startingAnInstanceCallsStartDbInstanceAndWaitsUntilAvailable() throws Exception {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenReturn(instance("stopped"), instance("starting"), instance("available"));
        List<String> stages = new ArrayList<>();

        VmOperationResult result = service.startVm("db:orders", TARGET, p -> stages.add(p.getStageLabel())).get();

        assertTrue(result.isSuccess(), result.getMessage());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());
        ArgumentCaptor<StartDbInstanceRequest> start = ArgumentCaptor.forClass(StartDbInstanceRequest.class);
        verify(rds).startDBInstance(start.capture());
        assertEquals("orders", start.getValue().dbInstanceIdentifier());
        verify(rds, never()).startDBCluster(any(StartDbClusterRequest.class));
        assertEquals(List.of("Database starting"), stages);
    }

    @Test
    void anAuroraClusterUsesTheClusterApi() throws Exception {
        when(rds.describeDBClusters(any(DescribeDbClustersRequest.class))).thenReturn(cluster("stopped"), cluster("available"));

        assertTrue(service.startVm("cluster:aurora-qa", TARGET).get().isSuccess());

        verify(rds).startDBCluster(argThat((StartDbClusterRequest r) -> "aurora-qa".equals(r.dbClusterIdentifier())));
        verify(rds, never()).startDBInstance(any(StartDbInstanceRequest.class));
    }

    @Test
    void stoppingAClusterCallsStopDbCluster() throws Exception {
        when(rds.describeDBClusters(any(DescribeDbClustersRequest.class)))
                .thenReturn(cluster("available"), cluster("stopping"), cluster("stopped"));

        VmOperationResult result = service.stopVm("cluster:aurora-qa", TARGET, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
        verify(rds).stopDBCluster(any(StopDbClusterRequest.class));
    }

    @Test
    void anAlreadyStoppedDatabaseIsASuccessWithoutACall() throws Exception {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(instance("stopped"));

        VmOperationResult result = service.stopVm("db:orders", TARGET, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());
        verify(rds, never()).stopDBInstance(any(StopDbInstanceRequest.class));
    }

    @Test
    void aStateRefusalWhileAlreadyStoppingWaitsForTheStop() throws Exception {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenReturn(instance("available"), instance("stopping"), instance("stopped"));
        when(rds.stopDBInstance(any(StopDbInstanceRequest.class))).thenThrow((InvalidDbInstanceStateException)
                InvalidDbInstanceStateException.builder().awsErrorDetails(AwsErrorDetails.builder()
                        .errorMessage("Instance orders is not in available state").build()).build());

        assertTrue(service.stopVm("db:orders", TARGET, false).get().isSuccess());
    }

    @Test
    void readReplicasClusterMembersAndMultiAzSqlServerAreNotStopped() throws Exception {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(
                instance(DBInstance.builder().dbInstanceIdentifier("replica").engine("mysql").dbInstanceStatus("available")
                        .readReplicaSourceDBInstanceIdentifier("orders").build()),
                instance(DBInstance.builder().dbInstanceIdentifier("aurora-1").engine("aurora-mysql").dbInstanceStatus("available")
                        .dbClusterIdentifier("aurora-qa").build()),
                instance(DBInstance.builder().dbInstanceIdentifier("mssql").engine("sqlserver-se").multiAZ(true)
                        .dbInstanceStatus("available").build()));

        VmOperationResult replica = service.stopVm("db:replica", TARGET, false).get();
        VmOperationResult member = service.stopVm("db:aurora-1", TARGET, false).get();
        VmOperationResult sqlServer = service.stopVm("db:mssql", TARGET, false).get();

        assertFalse(replica.isSuccess());
        assertTrue(replica.getMessage().contains("read replica"), replica.getMessage());
        assertFalse(member.isSuccess());
        assertTrue(member.getMessage().contains("Aurora cluster aurora-qa"), member.getMessage());
        assertFalse(sqlServer.isSuccess());
        assertTrue(sqlServer.getMessage().contains("Multi-AZ"), sqlServer.getMessage());
        verify(rds, never()).stopDBInstance(any(StopDbInstanceRequest.class));
    }

    @Test
    void aDatabaseThatNeverArrivesTimesOut() throws Exception {
        ReflectionTestUtils.setField(service, "pollIntervalMs", 20L);
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(instance("stopped"), instance("starting"));

        VmOperationResult result = service.startVm("db:orders", TARGET).get();

        assertFalse(result.isSuccess());
        assertTrue(result.isTimedOut());
        assertEquals(VmStatus.STARTING, result.getResultStatus());
    }

    @Test
    void aFailedDatabaseEndsTheWaitWithAFailure() throws Exception {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(instance("stopped"), instance("failed"));

        VmOperationResult result = service.startVm("db:orders", TARGET).get();

        assertFalse(result.isSuccess());
        assertFalse(result.isTimedOut());
        assertTrue(result.getMessage().contains("failed"));
    }

    @Test
    void switchedOffItIsUnavailableAndCallsNothing() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertFalse(service.isAvailable());
        VmOperationResult result = service.stopVm("db:orders", TARGET, false).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("rds.enabled"));
        assertEquals(VmStatus.UNKNOWN, service.getVmStatus("db:orders", TARGET));
        assertTrue(service.getVmStatusBatch(List.of("db:orders"), TARGET).isEmpty());
        verifyNoInteractions(rds);
    }

    @Test
    void aMissingDatabaseIsNotFound() {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class)))
                .thenThrow(DbInstanceNotFoundException.builder().message("gone").build());

        assertEquals(VmStatus.NOT_FOUND, service.getVmStatus("db:orders", TARGET));
        assertEquals(VmStatus.UNKNOWN, service.getVmStatus("orders", TARGET));
    }

    @Test
    void listingOffersClustersAndStandaloneInstancesButNotClusterMembers() {
        when(rds.describeDBClusters(any(DescribeDbClustersRequest.class))).thenReturn(
                DescribeDbClustersResponse.builder().dbClusters(DBCluster.builder().dbClusterIdentifier("aurora-qa")
                        .engine("aurora-postgresql").engineMode("provisioned").status("available").build()).marker("next").build(),
                DescribeDbClustersResponse.builder().dbClusters(DBCluster.builder().dbClusterIdentifier("legacy")
                        .engine("aurora-mysql").engineMode("serverless").status("available").build()).build());
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(DescribeDbInstancesResponse.builder().dbInstances(
                DBInstance.builder().dbInstanceIdentifier("aurora-qa-1").engine("aurora-postgresql").dbClusterIdentifier("aurora-qa")
                        .dbInstanceStatus("available").build(),
                DBInstance.builder().dbInstanceIdentifier("orders").engine("postgres").dbInstanceClass("db.t3.medium")
                        .dbInstanceStatus("stopped").build(),
                DBInstance.builder().dbInstanceIdentifier("orders-ro").engine("postgres").readReplicaSourceDBInstanceIdentifier("orders")
                        .dbInstanceStatus("available").build()).build());

        List<com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO> found = service.listDatabases(TARGET);

        assertEquals(List.of("cluster:aurora-qa", "cluster:legacy", "db:orders", "db:orders-ro"),
                found.stream().map(com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO::providerVmId).toList());
        assertTrue(found.get(0).stoppable());
        assertFalse(found.get(1).stoppable());
        assertEquals("db.t3.medium", found.get(2).instanceClass());
        assertEquals("INSTANCE", found.get(2).kind());
        assertFalse(found.get(3).stoppable());
        assertTrue(found.get(3).notStoppableReason().contains("read replica"));
        verify(rds, times(2)).describeDBClusters(any(DescribeDbClustersRequest.class));
    }

    @Test
    void theBatchMakesOneCallPerKindAndMarksMissingIds() {
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(instance("available"));
        when(rds.describeDBClusters(any(DescribeDbClustersRequest.class))).thenReturn(cluster("stopped"));

        Map<String, VmStatus> result = service.getVmStatusBatch(
                List.of("db:orders", "db:gone", "cluster:aurora-qa", "not-rds"), TARGET);

        assertEquals(Map.of("db:orders", VmStatus.RUNNING, "db:gone", VmStatus.NOT_FOUND,
                "cluster:aurora-qa", VmStatus.STOPPED), result);
        ArgumentCaptor<DescribeDbInstancesRequest> request = ArgumentCaptor.forClass(DescribeDbInstancesRequest.class);
        verify(rds, times(1)).describeDBInstances(request.capture());
        assertEquals("db-instance-id", request.getValue().filters().get(0).name());
        assertEquals(List.of("orders", "gone"), request.getValue().filters().get(0).values());
        verify(rds, times(1)).describeDBClusters(any(DescribeDbClustersRequest.class));
    }

    @Test
    void tagsAreWrittenOnTheDatabasesArn() {
        // E33-T05
        when(rds.describeDBInstances(any(DescribeDbInstancesRequest.class))).thenReturn(instance(DBInstance.builder()
                .dbInstanceIdentifier("orders").dbInstanceArn("arn:aws:rds:ap-south-1:111:db:orders").build()));

        assertTrue(service.tagResource(TARGET, "db:orders", Map.of("tcg:environment", "qa")));

        ArgumentCaptor<AddTagsToResourceRequest> request = ArgumentCaptor.forClass(AddTagsToResourceRequest.class);
        verify(rds).addTagsToResource(request.capture());
        assertEquals("arn:aws:rds:ap-south-1:111:db:orders", request.getValue().resourceName());
        assertEquals("tcg:environment", request.getValue().tags().get(0).key());
        assertEquals("qa", request.getValue().tags().get(0).value());
    }
}
