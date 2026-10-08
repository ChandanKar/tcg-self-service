package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.eks.model.Nodegroup;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagReconciliationServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private AwsCloudProviderService awsCloudProviderService;
    @Mock private EksCloudProviderService eksCloudProviderService;
    @Mock private CostExplorerTagActivationService tagActivationService;

    private final TeamResolver teamResolver = new TeamResolver(new ObjectMapper());

    private TagReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new TagReconciliationService(vmRepository, awsCloudProviderService,
                eksCloudProviderService, tagActivationService, teamResolver);
        ReflectionTestUtils.setField(service, "taggingEnabled", true);
        ReflectionTestUtils.setField(service, "tagKeyPrefix", "tcg:");
    }

    @Test
    void reconcileAll_doesNothingWhenTaggingDisabled() {
        ReflectionTestUtils.setField(service, "taggingEnabled", false);

        TagReconciliationService.Result result = service.reconcileAll();

        assertEquals(0, result.total());
        verify(vmRepository, never()).findByIsActiveTrueFetchGroupAndEnvironment();
        verify(awsCloudProviderService, never()).tagInstances(anyString(), any(), any());
    }

    @Test
    void reconcileAll_tagsEc2InstancesGroupedByRegionAndEnvironment() {
        Environment env = buildEnvironment("env-1", "prod-01", "{\"ownerTeam\":\"payments\"}");
        Vm vm1 = buildEc2Vm("vm-1", env, "i-aaa", "ap-south-1");
        Vm vm2 = buildEc2Vm("vm-2", env, "i-bbb", "ap-south-1");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm1, vm2));
        when(awsCloudProviderService.tagInstances(eq("ap-south-1"), any(), any())).thenReturn(Set.of("i-aaa", "i-bbb"));

        TagReconciliationService.Result result = service.reconcileAll();

        assertEquals(2, result.tagged());
        assertEquals(0, result.failed());

        ArgumentCaptor<Map<String, String>> tagsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(awsCloudProviderService).tagInstances(eq("ap-south-1"), eq(List.of("i-aaa", "i-bbb")), tagsCaptor.capture());
        Map<String, String> tags = tagsCaptor.getValue();
        assertEquals("vmcontrol", tags.get("tcg:managed-by"));
        assertEquals("prod-01", tags.get("tcg:environment"));
        assertEquals("payments", tags.get("tcg:team"));

        // Targeted update by id (E05-T04): the VMs loaded at the start of the run are never re-saved.
        verify(vmRepository).markTagsSynced(eq(List.of(vm1.getVmId(), vm2.getVmId())), any());
        verify(vmRepository, never()).saveAll(any());
    }

    @Test
    void reconcileAll_taggingFailureForOneGroupDoesNotBlockOthers() {
        Environment envA = buildEnvironment("env-a", "env-a-name", null);
        Environment envB = buildEnvironment("env-b", "env-b-name", null);
        Vm vmA = buildEc2Vm("vm-a", envA, "i-aaa", "ap-south-1");
        Vm vmB = buildEc2Vm("vm-b", envB, "i-bbb", "ap-south-1");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vmA, vmB));

        doThrow(new RuntimeException("AWS throttled"))
                .when(awsCloudProviderService).tagInstances(eq("ap-south-1"), eq(List.of("i-aaa")), any());
        when(awsCloudProviderService.tagInstances(eq("ap-south-1"), eq(List.of("i-bbb")), any())).thenReturn(Set.of("i-bbb"));

        TagReconciliationService.Result result = service.reconcileAll();

        assertEquals(1, result.tagged());
        assertEquals(1, result.failed());
        assertEquals(2, result.total());
    }

    @Test
    void reconcileAll_tagsEksNodeGroupUsingResolvedArn() {
        Environment env = buildEnvironment("env-eks", "eks-env", null);
        Vm vm = buildEksVm("vm-eks", env, "my-cluster/my-nodegroup", "ap-south-1");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm));

        Nodegroup nodegroup = Nodegroup.builder()
                .clusterName("my-cluster")
                .nodegroupName("my-nodegroup")
                .nodegroupArn("arn:aws:eks:ap-south-1:123456789012:nodegroup/my-cluster/my-nodegroup/abc123")
                .build();
        when(eksCloudProviderService.describeNodegroup("my-cluster", "my-nodegroup", "ap-south-1")).thenReturn(nodegroup);

        TagReconciliationService.Result result = service.reconcileAll();

        assertEquals(1, result.tagged());
        verify(eksCloudProviderService).tagNodeGroup(
                eq("arn:aws:eks:ap-south-1:123456789012:nodegroup/my-cluster/my-nodegroup/abc123"),
                eq("ap-south-1"), any());
    }

    @Test
    void reconcileAll_skipsEksNodeGroupWhenArnCannotBeResolved() {
        Environment env = buildEnvironment("env-eks", "eks-env", null);
        Vm vm = buildEksVm("vm-eks", env, "my-cluster/my-nodegroup", "ap-south-1");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm));
        when(eksCloudProviderService.describeNodegroup(anyString(), anyString(), anyString())).thenReturn(null);

        TagReconciliationService.Result result = service.reconcileAll();

        assertEquals(0, result.tagged());
        assertEquals(1, result.failed());
        verify(eksCloudProviderService, never()).tagNodeGroup(anyString(), anyString(), any());
    }

    @Test
    void reconcileEnvironment_onlyTagsVmsInThatEnvironment() {
        Environment envA = buildEnvironment("env-a", "env-a-name", null);
        Environment envB = buildEnvironment("env-b", "env-b-name", null);
        Vm vmA = buildEc2Vm("vm-a", envA, "i-aaa", "ap-south-1");
        Vm vmB = buildEc2Vm("vm-b", envB, "i-bbb", "ap-south-1");
        when(vmRepository.findActiveByEnvironmentIdFetchGroupAndEnvironment("env-a", false)).thenReturn(List.of(vmA));

        TagReconciliationService.Result result = service.reconcileEnvironment(envA);

        assertEquals(1, result.total());
        verify(awsCloudProviderService).tagInstances(eq("ap-south-1"), eq(List.of("i-aaa")), any());
        verify(awsCloudProviderService, never()).tagInstances(eq("ap-south-1"), eq(List.of("i-bbb")), any());
        verify(vmRepository, never()).findByIsActiveTrueFetchGroupAndEnvironment(); // no fleet-wide load
    }

    // ---- E09-T04: after discovery only untagged VMs; one bad id does not fail the batch ----

    @Test
    void reconcileNewInEnvironment_tagsOnlyUntaggedVms() {
        Environment env = buildEnvironment("env-a", "env-a-name", null);
        Vm fresh = buildEc2Vm("vm-new", env, "i-new", "ap-south-1");
        when(vmRepository.findActiveByEnvironmentIdFetchGroupAndEnvironment("env-a", true)).thenReturn(List.of(fresh));
        when(awsCloudProviderService.tagInstances(eq("ap-south-1"), any(), any())).thenReturn(Set.of("i-new"));

        TagReconciliationService.Result result = service.reconcileNewInEnvironment(env);

        assertEquals(1, result.tagged());
        verify(awsCloudProviderService).tagInstances(eq("ap-south-1"), eq(List.of("i-new")), any());
        verify(vmRepository, never()).findActiveByEnvironmentIdFetchGroupAndEnvironment("env-a", false);
    }

    @Test
    void reconcile_marksOnlyTheIdsAwsAccepted() {
        Environment env = buildEnvironment("env-a", "env-a-name", null);
        Vm good = buildEc2Vm("vm-good", env, "i-good", "ap-south-1");
        Vm gone = buildEc2Vm("vm-gone", env, "i-gone", "ap-south-1");
        when(vmRepository.findActiveByEnvironmentIdFetchGroupAndEnvironment("env-a", true)).thenReturn(List.of(good, gone));
        when(awsCloudProviderService.tagInstances(eq("ap-south-1"), any(), any())).thenReturn(Set.of("i-good"));

        TagReconciliationService.Result result = service.reconcileNewInEnvironment(env);

        assertEquals(1, result.tagged());
        assertEquals(1, result.failed());
        verify(vmRepository).markTagsSynced(eq(List.of("vm-good")), any());
        verify(vmRepository, never()).save(any());
        verify(vmRepository, never()).saveAll(any());
    }

    // ---- helpers ----

    private Environment buildEnvironment(String id, String name, String metadata) {
        Environment env = new Environment();
        env.setEnvironmentId(id);
        env.setName(name);
        env.setMetadata(metadata);
        return env;
    }

    private Vm buildEc2Vm(String vmId, Environment env, String instanceId, String region) {
        VmGroup group = new VmGroup();
        group.setGroupId(vmId + "-grp");
        group.setEnvironment(env);

        Vm vm = new Vm();
        vm.setVmId(vmId);
        vm.setGroup(group);
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId(instanceId);
        vm.setRegion(region);
        return vm;
    }

    private Vm buildEksVm(String vmId, Environment env, String providerVmId, String region) {
        VmGroup group = new VmGroup();
        group.setGroupId(vmId + "-grp");
        group.setEnvironment(env);

        Vm vm = new Vm();
        vm.setVmId(vmId);
        vm.setGroup(group);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId(providerVmId);
        vm.setRegion(region);
        return vm;
    }
}
