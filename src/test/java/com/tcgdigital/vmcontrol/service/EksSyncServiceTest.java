package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.eks.model.Nodegroup;
import software.amazon.awssdk.services.eks.model.NodegroupScalingConfig;
import software.amazon.awssdk.services.eks.model.NodegroupStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EksSyncServiceTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private VmGroupRepository groupRepository;
    @Mock private VmRepository vmRepository;
    @Mock private EksCloudProviderService eksService;
    @Mock private AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EksSyncService service;

    private static final String CLUSTER  = "prod-cluster";
    private static final String NODEGROUP = "backend-workers";
    private static final String REGION   = "ap-south-1";
    private static final String ENV_ID   = "env-001";

    @BeforeEach
    void setUp() {
        service = new EksSyncService(environmentRepository, groupRepository, vmRepository,
                eksService, auditService, objectMapper);
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
    }

    // ---- syncEksEnvironment tests ----

    @Test
    void syncEksEnvironment_createsVmGroupAndVmForNewNodeGroup() {
        Environment env = buildEnvironment();
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(2, 4));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.empty());
        when(vmRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID)).thenReturn(List.of());

        int count = service.syncEksEnvironment(env);

        assertEquals(1, count);
        verify(groupRepository).save(any(VmGroup.class));
        verify(vmRepository).save(any(Vm.class));
    }

    @Test
    void syncEksEnvironment_enrichesVmGroupMetadataWithClusterIdentity() throws Exception {
        Environment env = buildEnvironment();
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(1, 2));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID)).thenReturn(List.of());
        ArgumentCaptor<VmGroup> groupCaptor = ArgumentCaptor.forClass(VmGroup.class);
        when(groupRepository.save(groupCaptor.capture())).thenAnswer(i -> i.getArgument(0));
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.empty());
        when(vmRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.syncEksEnvironment(env);

        VmGroup saved = groupCaptor.getValue();
        assertNotNull(saved.getMetadata(), "VmGroup metadata must be set");
        var meta = objectMapper.readValue(saved.getMetadata(), java.util.Map.class);
        assertEquals(CLUSTER,   meta.get("clusterName"));
        assertEquals(NODEGROUP, meta.get("nodeGroupName"));
        assertEquals(REGION,    meta.get("region"));
    }

    @Test
    void syncEksEnvironment_savesMinSizeAndDesiredSizeToVmMetadataWhenRunning() throws Exception {
        Environment env = buildEnvironment();
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(2, 5));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID)).thenReturn(List.of());
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.empty());
        ArgumentCaptor<Vm> vmCaptor = ArgumentCaptor.forClass(Vm.class);
        when(vmRepository.save(vmCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.syncEksEnvironment(env);

        Vm saved = vmCaptor.getValue();
        assertNotNull(saved.getMetadata(), "Vm metadata must be set when cluster is running");
        var meta = objectMapper.readValue(saved.getMetadata(), java.util.Map.class);
        assertEquals(2, ((Number) meta.get("minSize")).intValue());
        assertEquals(5, ((Number) meta.get("desiredSize")).intValue());
    }

    @Test
    void syncEksEnvironment_doesNotOverwriteVmMetadataWhenClusterIsStopped() {
        Environment env = buildEnvironment();
        // Live state: desired=0 (stopped)
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(0, 0));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID)).thenReturn(List.of());
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Existing Vm has saved restore-point metadata
        Vm existingVm = new Vm();
        existingVm.setVmId("vm-existing");
        existingVm.setStatus(VmStatus.STOPPED);
        existingVm.setMetadata("{\"minSize\":3,\"desiredSize\":6}");
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.of(existingVm));
        ArgumentCaptor<Vm> vmCaptor = ArgumentCaptor.forClass(Vm.class);
        when(vmRepository.save(vmCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.syncEksEnvironment(env);

        Vm saved = vmCaptor.getValue();
        // Metadata must NOT be changed — preserve the restore-point
        assertEquals("{\"minSize\":3,\"desiredSize\":6}", saved.getMetadata());
    }

    @Test
    void syncEksEnvironment_detectsDriftWhenStatusChanges() {
        Environment env = buildEnvironment();
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        // Live: ACTIVE + desired=2 → RUNNING
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(1, 2));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID)).thenReturn(List.of());
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // DB says STOPPED
        Vm existingVm = new Vm();
        existingVm.setVmId("vm-drift");
        existingVm.setStatus(VmStatus.STOPPED);
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.of(existingVm));
        ArgumentCaptor<Vm> vmCaptor = ArgumentCaptor.forClass(Vm.class);
        when(vmRepository.save(vmCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.syncEksEnvironment(env);

        Vm saved = vmCaptor.getValue();
        assertTrue(saved.getStateDriftDetected(), "Drift must be flagged when DB and live status differ");
        assertEquals(VmStatus.RUNNING, saved.getStatus());
        verify(auditService).logAction(any(), eq(AuditAction.STATE_DRIFT_DETECTED), any(), any(), any(), any());
    }

    @Test
    void syncEksEnvironment_deactivatesNodeGroupRemovedFromCluster() {
        Environment env = buildEnvironment();
        // AWS only has NODEGROUP; staleGroup no longer exists there
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of(NODEGROUP));
        when(eksService.describeNodegroup(CLUSTER, NODEGROUP, REGION)).thenReturn(buildNodegroup(1, 1));
        when(groupRepository.findByEnvironmentEnvironmentIdAndName(ENV_ID, NODEGROUP)).thenReturn(Optional.empty());
        when(groupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vmRepository.findByGroupGroupIdAndName(anyString(), eq(NODEGROUP))).thenReturn(Optional.empty());
        when(vmRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // staleGroup is in the DB but not in AWS
        VmGroup staleGroup = new VmGroup();
        staleGroup.setGroupId("grp-stale");
        staleGroup.setName("old-workers");
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(ENV_ID))
                .thenReturn(List.of(staleGroup));

        Vm staleVm = new Vm();
        staleVm.setVmId("vm-stale");
        staleVm.setIsActive(true);
        staleVm.setStatus(VmStatus.RUNNING);
        when(vmRepository.findByGroupGroupIdOrderBySequencePositionAsc("grp-stale"))
                .thenReturn(List.of(staleVm));

        ArgumentCaptor<Vm> vmCaptor = ArgumentCaptor.forClass(Vm.class);
        when(vmRepository.save(vmCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.syncEksEnvironment(env);

        // Find the stale Vm save (isActive=false)
        boolean deactivated = vmCaptor.getAllValues().stream()
                .anyMatch(v -> "vm-stale".equals(v.getVmId()) && Boolean.FALSE.equals(v.getIsActive()));
        assertTrue(deactivated, "Stale VM must be deactivated");
    }

    @Test
    void syncEksEnvironment_skipsWhenNoNodeGroupsReturned() {
        Environment env = buildEnvironment();
        when(eksService.listNodegroups(CLUSTER, REGION)).thenReturn(List.of());

        int count = service.syncEksEnvironment(env);

        assertEquals(0, count);
        verifyNoInteractions(groupRepository, vmRepository);
    }

    @Test
    void syncAllEksClusters_skipsWhenProviderNotAvailable() {
        when(eksService.isAvailable()).thenReturn(false);

        int count = service.syncAllEksClusters();

        assertEquals(-1, count);
        verifyNoInteractions(environmentRepository);
    }

    // ---- helpers ----

    private Environment buildEnvironment() {
        Environment env = new Environment();
        env.setEnvironmentId(ENV_ID);
        env.setName(CLUSTER);
        env.setServiceType("EKS");
        env.setIsActive(true);
        env.setMetadata("{\"region\":\"" + REGION + "\"}");
        return env;
    }

    private Nodegroup buildNodegroup(int minSize, int desiredSize) {
        return Nodegroup.builder()
                .clusterName(CLUSTER)
                .nodegroupName(NODEGROUP)
                .status(desiredSize > 0 ? NodegroupStatus.ACTIVE : NodegroupStatus.ACTIVE)
                .scalingConfig(NodegroupScalingConfig.builder()
                        .minSize(minSize)
                        .desiredSize(desiredSize)
                        .maxSize(10)
                        .build())
                .build();
    }
}
