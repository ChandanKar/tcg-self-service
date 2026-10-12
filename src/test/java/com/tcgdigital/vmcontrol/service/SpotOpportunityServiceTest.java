package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.SpotOpportunityDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spot opportunities (E31-T04): who is listed, what Spot would save, and the placement score. */
class SpotOpportunityServiceTest {

    private final VmRepository vmRepository = mock(VmRepository.class);
    private final VmInventorySnapshotRepository inventory = mock(VmInventorySnapshotRepository.class);
    private final CostDataProvider costs = mock(CostDataProvider.class);
    private final SpotPriceService spotPrices = mock(SpotPriceService.class);
    private final SpotPolicyService policy = mock(SpotPolicyService.class);
    private final AwsCloudProviderService aws = mock(AwsCloudProviderService.class);
    private SpotOpportunityService service;
    private Environment allowed;

    @BeforeEach
    void setUp() {
        PricingReferenceService pricing = new PricingReferenceService();
        pricing.loadPricing();
        service = new SpotOpportunityService(vmRepository, inventory, costs, pricing, spotPrices, policy, aws);
        when(policy.isEnabled()).thenReturn(true);
        allowed = env("e-1", SpotPolicy.ALLOWED);
        when(spotPrices.lookupSpotRate("m6i.large", "us-east-1", null)).thenReturn(Optional.of(new BigDecimal("0.04")));
        when(aws.getSpotPlacementScore(any(), anyList(), anyInt())).thenReturn(8);
    }

    private static Environment env(String id, SpotPolicy p) {
        Environment e = new Environment();
        e.setEnvironmentId(id);
        e.setDisplayName("Env " + id);
        e.setSpotPolicy(p);
        return e;
    }

    private static Vm nodeGroup(String id, Environment env) {
        VmGroup g = new VmGroup();
        g.setEnvironment(env);
        Vm vm = new Vm(id);
        vm.setGroup(g);
        vm.setDisplayName(id);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setRegion("us-east-1");
        vm.setMetadata("{\"desiredSize\":3,\"instanceType\":\"m6i.large\"}");
        return vm;
    }

    private void hours(String vmId, String runtimeHours) {
        when(costs.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(vmId,
                new CostDataProvider.VmCostEstimate(true, new BigDecimal("0.288"), new BigDecimal(runtimeHours), 0, BigDecimal.TEN)));
    }

    @Test
    void anOnDemandNodeGroupInAnAllowedEnvironmentIsListedWithItsSavingAndScore() {
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(nodeGroup("ng-1", allowed)));
        hours("ng-1", "100");

        List<SpotOpportunityDTO> rows = service.list();

        assertThat(rows).hasSize(1);
        SpotOpportunityDTO row = rows.get(0);
        assertThat(row.kind()).isEqualTo("EKS_NODE_GROUP");
        assertThat(row.nodeCount()).isEqualTo(3);
        // on demand 0.096 x 100 h x 3 = 28.80; Spot 0.04 x 100 x 3 = 12.00
        assertThat(row.onDemandMonthly()).isEqualByComparingTo("28.80");
        assertThat(row.spotMonthly()).isEqualByComparingTo("12.00");
        assertThat(row.savings()).isEqualByComparingTo("16.80");
        assertThat(row.placementScore()).isEqualTo(8);
        assertThat(row.eligible()).isTrue();
    }

    @Test
    void excludedSpotAlreadyAndOtherEnvironmentsAreLeftOut() {
        Vm excluded = nodeGroup("ng-x", allowed);
        excluded.setSpotExcluded(true);
        Vm onSpot = nodeGroup("ng-s", allowed);
        onSpot.setPurchaseOption(PurchaseOption.SPOT);
        Vm notAllowed = nodeGroup("ng-n", env("e-2", SpotPolicy.NONE));
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(excluded, onSpot, notAllowed));

        assertThat(service.list()).isEmpty();
    }

    @Test
    void anEc2VmIsListedForInformationOnly() {
        Vm ec2 = nodeGroup("vm-1", allowed);
        ec2.setProvider(CloudProvider.AWS);
        ec2.setMetadata(null);
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(ec2));
        VmInventorySnapshotRepository.InstanceTypeProjection p = mock(VmInventorySnapshotRepository.InstanceTypeProjection.class);
        when(p.getVmId()).thenReturn("vm-1");
        when(p.getInstanceType()).thenReturn("m6i.large");
        when(inventory.findInstanceTypesByVmIds(anyList())).thenReturn(List.of(p));
        hours("vm-1", "50");

        SpotOpportunityDTO row = service.list().get(0);

        assertThat(row.kind()).isEqualTo("EC2");
        assertThat(row.eligible()).isFalse();
        assertThat(row.reason()).isEqualTo("Requires relaunch as a Spot instance");
        assertThat(row.savings()).isEqualByComparingTo("2.80");
    }

    @Test
    void thePlacementScoreIsAskedOnceADayAndUnknownWhenAwsRefuses() {
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(nodeGroup("ng-1", allowed)));
        hours("ng-1", "10");
        when(aws.getSpotPlacementScore(any(), anyList(), anyInt())).thenThrow(new RuntimeException("AccessDenied"));

        assertThat(service.list().get(0).placementScore()).isNull();
        assertThat(service.list().get(0).placementScore()).isNull();
        verify(aws, times(1)).getSpotPlacementScore(any(), anyList(), anyInt());
    }

    @Test
    void withSpotOffNothingIsListed() {
        when(policy.isEnabled()).thenReturn(false);

        assertThat(service.list()).isEmpty();
        verify(vmRepository, never()).findByIsActiveTrueFetchGroupAndEnvironment();
    }
}
