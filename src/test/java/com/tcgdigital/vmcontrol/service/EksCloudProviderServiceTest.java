package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.CloudProviderService.VmOperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EksCloudProviderServiceTest {

    @Mock
    private EksClient mockEksClient;

    @Mock
    private VmRepository vmRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EksCloudProviderService service;

    private static final String CLUSTER    = "my-cluster";
    private static final String NODEGROUP  = "workers";
    private static final String PROVIDER_VM_ID = CLUSTER + "/" + NODEGROUP;
    private static final String REGION     = "ap-south-1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new EksCloudProviderService(vmRepository, objectMapper);
        ReflectionTestUtils.setField(service, "accessKey", "test-key");
        ReflectionTestUtils.setField(service, "secretKey", "test-secret");
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
        ReflectionTestUtils.setField(service, "defaultMinSize", 1);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 0L);
        ReflectionTestUtils.setField(service, "operationTimeoutMs", 500L);

        Map<String, EksClient> clientCache =
                (Map<String, EksClient>) ReflectionTestUtils.getField(service, "clientCache");
        clientCache.put(REGION, mockEksClient);
    }

    // ---- startVm tests ----

    @Test
    void startVm_restoresMinSizeAsDesiredSize() throws Exception {
        Vm vm = new Vm();
        vm.setMetadata("{\"minSize\":3,\"desiredSize\":5}");
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, PROVIDER_VM_ID))
                .thenReturn(Optional.of(vm));

        UpdateNodegroupConfigResponse updateResp = UpdateNodegroupConfigResponse.builder()
                .update(Update.builder().id("upd-001").build())
                .build();
        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(updateResp);

        // Poll returns ACTIVE with desired=3 on first poll
        mockDescribeActive(3);

        VmOperationResult result = service.startVm(PROVIDER_VM_ID, REGION).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.RUNNING, result.getResultStatus());

        ArgumentCaptor<UpdateNodegroupConfigRequest> captor =
                ArgumentCaptor.forClass(UpdateNodegroupConfigRequest.class);
        verify(mockEksClient).updateNodegroupConfig(captor.capture());

        NodegroupScalingConfig scaling = captor.getValue().scalingConfig();
        assertEquals(3, scaling.minSize(),     "minSize must be restored from saved metadata");
        assertEquals(3, scaling.desiredSize(), "desiredSize must equal minSize on start");
    }

    @Test
    void startVm_fallsBackToDefaultMinSizeWhenNoMetadata() throws Exception {
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, PROVIDER_VM_ID))
                .thenReturn(Optional.empty());

        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(UpdateNodegroupConfigResponse.builder()
                        .update(Update.builder().id("upd-002").build()).build());
        mockDescribeActive(1);

        service.startVm(PROVIDER_VM_ID, REGION).get();

        ArgumentCaptor<UpdateNodegroupConfigRequest> captor =
                ArgumentCaptor.forClass(UpdateNodegroupConfigRequest.class);
        verify(mockEksClient).updateNodegroupConfig(captor.capture());

        assertEquals(1, captor.getValue().scalingConfig().minSize());
        assertEquals(1, captor.getValue().scalingConfig().desiredSize());
    }

    @Test
    void startVm_usesDefaultWhenMetadataMinSizeIsZero() throws Exception {
        Vm vm = new Vm();
        vm.setMetadata("{\"minSize\":0,\"desiredSize\":0}");
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, PROVIDER_VM_ID))
                .thenReturn(Optional.of(vm));

        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(UpdateNodegroupConfigResponse.builder()
                        .update(Update.builder().id("upd-003").build()).build());
        mockDescribeActive(1);

        service.startVm(PROVIDER_VM_ID, REGION).get();

        ArgumentCaptor<UpdateNodegroupConfigRequest> captor =
                ArgumentCaptor.forClass(UpdateNodegroupConfigRequest.class);
        verify(mockEksClient).updateNodegroupConfig(captor.capture());
        assertEquals(1, captor.getValue().scalingConfig().minSize(), "zero saved minSize must fall back to default");
    }

    @Test
    void startVm_returnsFailureForInvalidProviderVmId() throws Exception {
        VmOperationResult result = service.startVm("no-slash-here", REGION).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Invalid providerVmId format"));
        verifyNoInteractions(mockEksClient);
    }

    // ---- stopVm tests ----

    @Test
    void stopVm_savesLiveMinSizeBeforeScalingToZero() throws Exception {
        Vm vm = new Vm();
        vm.setVmId("vm-123");
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, PROVIDER_VM_ID))
                .thenReturn(Optional.of(vm));

        // First describeNodegroup call = saveScalingConfigToMetadata (live state: min=2, desired=4)
        DescribeNodegroupResponse liveDesc = buildDescribeResponse(NodegroupStatus.ACTIVE, 2, 4);
        // Second call in poll loop = node group reached desired=0
        DescribeNodegroupResponse stoppedDesc = buildDescribeResponse(NodegroupStatus.ACTIVE, 0, 0);
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(liveDesc, stoppedDesc);

        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(UpdateNodegroupConfigResponse.builder()
                        .update(Update.builder().id("upd-stop").build()).build());

        VmOperationResult result = service.stopVm(PROVIDER_VM_ID, REGION, false).get();

        assertTrue(result.isSuccess());
        assertEquals(VmStatus.STOPPED, result.getResultStatus());

        // Verify metadata was saved with live values
        ArgumentCaptor<Vm> vmCaptor = ArgumentCaptor.forClass(Vm.class);
        verify(vmRepository).save(vmCaptor.capture());
        String savedMeta = vmCaptor.getValue().getMetadata();
        assertNotNull(savedMeta);
        assertTrue(savedMeta.contains("\"minSize\":2"), "minSize=2 must be saved");
        assertTrue(savedMeta.contains("\"desiredSize\":4"), "desiredSize=4 must be saved");

        // Verify scale-to-zero was called
        ArgumentCaptor<UpdateNodegroupConfigRequest> updateCaptor =
                ArgumentCaptor.forClass(UpdateNodegroupConfigRequest.class);
        verify(mockEksClient).updateNodegroupConfig(updateCaptor.capture());
        assertEquals(0, updateCaptor.getValue().scalingConfig().minSize());
        assertEquals(0, updateCaptor.getValue().scalingConfig().desiredSize());
    }

    @Test
    void stopVm_skipsMetadataSaveWhenAlreadyAtZero() throws Exception {
        // Live state shows node group already at 0 (double-stop guard)
        DescribeNodegroupResponse alreadyZero = buildDescribeResponse(NodegroupStatus.ACTIVE, 0, 0);
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(alreadyZero);
        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(UpdateNodegroupConfigResponse.builder()
                        .update(Update.builder().id("upd-noop").build()).build());

        service.stopVm(PROVIDER_VM_ID, REGION, false).get();

        // vmRepository.save must NOT be called (nothing useful to preserve)
        verify(vmRepository, never()).save(any());
    }

    @Test
    void stopVm_continuesWhenMetadataSaveFails() throws Exception {
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, PROVIDER_VM_ID))
                .thenReturn(Optional.empty()); // VM not found in DB

        DescribeNodegroupResponse liveDesc = buildDescribeResponse(NodegroupStatus.ACTIVE, 1, 2);
        DescribeNodegroupResponse stoppedDesc = buildDescribeResponse(NodegroupStatus.ACTIVE, 0, 0);
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(liveDesc, stoppedDesc);
        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenReturn(UpdateNodegroupConfigResponse.builder()
                        .update(Update.builder().id("upd-cont").build()).build());

        // Stop must still succeed even though metadata save was skipped
        VmOperationResult result = service.stopVm(PROVIDER_VM_ID, REGION, false).get();
        assertTrue(result.isSuccess());
    }

    // ---- getVmStatus / status-mapping tests ----

    @Test
    void getVmStatus_activeWithDesiredGtZeroReturnsRUNNING() {
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(buildDescribeResponse(NodegroupStatus.ACTIVE, 1, 3));

        assertEquals(VmStatus.RUNNING, service.getVmStatus(PROVIDER_VM_ID, REGION));
    }

    @Test
    void getVmStatus_activeWithDesiredZeroReturnsSTOPPED() {
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(buildDescribeResponse(NodegroupStatus.ACTIVE, 0, 0));

        assertEquals(VmStatus.STOPPED, service.getVmStatus(PROVIDER_VM_ID, REGION));
    }

    @Test
    void mapNodegroupToVmStatus_coversAllStatusValues() {
        assertEquals(VmStatus.RUNNING,  service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.ACTIVE, 2)));
        assertEquals(VmStatus.STOPPED,  service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.ACTIVE, 0)));
        assertEquals(VmStatus.STARTING, service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.CREATING, 0)));
        assertEquals(VmStatus.STARTING, service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.UPDATING, 0)));
        assertEquals(VmStatus.STOPPING, service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.DELETING, 0)));
        assertEquals(VmStatus.STOPPING, service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.DEGRADED, 0)));
        assertEquals(VmStatus.ERROR,    service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.CREATE_FAILED, 0)));
        assertEquals(VmStatus.ERROR,    service.mapNodegroupToVmStatus(buildNodegroup(NodegroupStatus.DELETE_FAILED, 0)));
        assertEquals(VmStatus.NOT_FOUND, service.mapNodegroupToVmStatus(null));
    }

    @Test
    void listNodegroups_followsAwsPagination() {
        when(mockEksClient.listNodegroups(any(ListNodegroupsRequest.class)))
                .thenReturn(
                        ListNodegroupsResponse.builder()
                                .nodegroups("ng-a")
                                .nextToken("page-2")
                                .build(),
                        ListNodegroupsResponse.builder()
                                .nodegroups("ng-b", "ng-c")
                                .build());

        List<String> nodegroups = service.listNodegroups(CLUSTER, REGION);

        assertEquals(List.of("ng-a", "ng-b", "ng-c"), nodegroups);
        ArgumentCaptor<ListNodegroupsRequest> captor = ArgumentCaptor.forClass(ListNodegroupsRequest.class);
        verify(mockEksClient, times(2)).listNodegroups(captor.capture());
        assertNull(captor.getAllValues().get(0).nextToken());
        assertEquals("page-2", captor.getAllValues().get(1).nextToken());
    }

    @Test
    void listClusters_followsAwsPagination() {
        when(mockEksClient.listClusters(any(ListClustersRequest.class)))
                .thenReturn(
                        ListClustersResponse.builder()
                                .clusters("cluster-a")
                                .nextToken("page-2")
                                .build(),
                        ListClustersResponse.builder()
                                .clusters("cluster-b", "cluster-c")
                                .build());

        List<String> clusters = service.listClusters(REGION);

        assertEquals(List.of("cluster-a", "cluster-b", "cluster-c"), clusters);
        ArgumentCaptor<ListClustersRequest> captor = ArgumentCaptor.forClass(ListClustersRequest.class);
        verify(mockEksClient, times(2)).listClusters(captor.capture());
        assertNull(captor.getAllValues().get(0).nextToken());
        assertEquals("page-2", captor.getAllValues().get(1).nextToken());
    }

    @Test
    void startVm_returnsErrorOnEksException() throws Exception {
        when(vmRepository.findByProviderAndProviderVmId(any(), any())).thenReturn(Optional.empty());
        when(mockEksClient.updateNodegroupConfig(any(UpdateNodegroupConfigRequest.class)))
                .thenThrow(EksException.builder()
                        .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                                .errorMessage("Cluster not found").build())
                        .build());

        VmOperationResult result = service.startVm(PROVIDER_VM_ID, REGION).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Cluster not found"));
    }

    // ---- helpers ----

    private void mockDescribeActive(int desiredSize) {
        when(mockEksClient.describeNodegroup(any(DescribeNodegroupRequest.class)))
                .thenReturn(buildDescribeResponse(NodegroupStatus.ACTIVE, desiredSize, desiredSize));
    }

    private DescribeNodegroupResponse buildDescribeResponse(NodegroupStatus status, int minSize, int desiredSize) {
        return DescribeNodegroupResponse.builder()
                .nodegroup(buildNodegroup(status, desiredSize, minSize))
                .build();
    }

    private Nodegroup buildNodegroup(NodegroupStatus status, int desiredSize) {
        return buildNodegroup(status, desiredSize, 0);
    }

    private Nodegroup buildNodegroup(NodegroupStatus status, int desiredSize, int minSize) {
        return Nodegroup.builder()
                .clusterName(CLUSTER)
                .nodegroupName(NODEGROUP)
                .status(status)
                .scalingConfig(NodegroupScalingConfig.builder()
                        .minSize(minSize)
                        .desiredSize(desiredSize)
                        .maxSize(10)
                        .build())
                .build();
    }
}
