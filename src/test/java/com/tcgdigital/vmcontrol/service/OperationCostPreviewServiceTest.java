package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostPreviewDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Start cost preview (E25-T01): rounding, running VMs, unknown prices, hours, EKS node groups. */
class OperationCostPreviewServiceTest {

    private static final String ENV = "env-1";
    private static final String GROUP = "grp-1";

    private VmRepository vms;
    private VmInventorySnapshotRepository inventory;
    private PricingReferenceService pricing;
    private EstimatedCostProvider eks;
    private LeaseService leases;
    private OperationCostPreviewService service;
    private Environment environment;
    private VmGroup group;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        VmGroupRepository groups = mock(VmGroupRepository.class);
        vms = mock(VmRepository.class);
        inventory = mock(VmInventorySnapshotRepository.class);
        pricing = mock(PricingReferenceService.class);
        eks = mock(EstimatedCostProvider.class);
        SecurityService security = mock(SecurityService.class);
        leases = mock(LeaseService.class);
        ObjectProvider<LeaseService> leaseProvider = mock(ObjectProvider.class);
        when(leaseProvider.getIfAvailable()).thenReturn(leases);

        environment = new Environment(ENV);
        group = new VmGroup(GROUP);
        group.setEnvironment(environment);
        when(environments.findById(ENV)).thenReturn(Optional.of(environment));
        when(groups.findById(GROUP)).thenReturn(Optional.of(group));
        when(security.getVisibleGroupIds(ENV)).thenReturn(List.of(GROUP));
        when(pricing.lookupHourlyRate(eq("AWS"), eq("m5.large"), any()))
                .thenReturn(new PricingReferenceService.PriceLookupResult(true, new BigDecimal("0.096"), false));
        when(pricing.lookupHourlyRate(eq("AWS"), eq("x9.huge"), any()))
                .thenReturn(new PricingReferenceService.PriceLookupResult(false, null, false));
        service = new OperationCostPreviewService(environments, groups, vms, inventory, pricing, eks, security, leaseProvider);
    }

    private Vm vm(String id, VmStatus status, CloudProvider provider) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setName(id);
        vm.setGroup(group);
        vm.setStatus(status);
        vm.setProvider(provider);
        vm.setRegion("ap-south-1");
        return vm;
    }

    private void inGroup(Vm... list) {
        when(vms.findByGroupGroupIdOrderBySequencePositionAsc(GROUP)).thenReturn(List.of(list));
    }

    private void types(String... pairs) {
        List<VmInventorySnapshotRepository.InstanceTypeProjection> projections = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String id = pairs[i];
            String type = pairs[i + 1];
            projections.add(new VmInventorySnapshotRepository.InstanceTypeProjection() {
                public String getVmId() { return id; }
                public String getInstanceType() { return type; }
                public Timestamp getInstanceTypeChangedAt() { return null; }
                public Integer getMemoryMib() { return null; }
                public String getArchitecture() { return null; }
            });
        }
        when(inventory.findInstanceTypesByVmIds(anyList())).thenReturn(projections);
    }

    @Test
    void twoStoppedVmsRoundedPerVmThenSummed() {
        inGroup(vm("a", VmStatus.STOPPED, CloudProvider.AWS), vm("b", VmStatus.STOPPED, CloudProvider.AWS));
        types("a", "m5.large", "b", "m5.large");

        CostPreviewDTO preview = service.preview(ENV, GROUP, null, 8);
        assertThat(preview.hourlyTotal()).isEqualByComparingTo("0.19");
        assertThat(preview.estimatedTotal()).isEqualByComparingTo("1.54");
        assertThat(preview.vms()).extracting(CostPreviewDTO.VmLine::estimatedCost)
                .allSatisfy(c -> assertThat(c).isEqualByComparingTo("0.77"));
        assertThat(preview.currency()).isEqualTo("USD");
    }

    @Test
    void runningVmsAddNothingAndUnknownPricesAreCounted() {
        inGroup(vm("a", VmStatus.RUNNING, CloudProvider.AWS), vm("b", VmStatus.STOPPED, CloudProvider.AWS),
                vm("c", VmStatus.STOPPED, CloudProvider.AWS));
        types("a", "m5.large", "b", "m5.large", "c", "x9.huge");

        CostPreviewDTO preview = service.preview(ENV, GROUP, null, 10);
        assertThat(preview.alreadyRunningCount()).isEqualTo(1);
        assertThat(preview.vms()).extracting(CostPreviewDTO.VmLine::vmId).containsExactly("b", "c");
        assertThat(preview.unknownPriceCount()).isEqualTo(1);
        assertThat(preview.vms().get(1).priceKnown()).isFalse();
        assertThat(preview.estimatedTotal()).isEqualByComparingTo("0.96");
    }

    @Test
    void hoursAreClampedAndDefaultToTheLeaseOrTheSetting() {
        inGroup(vm("a", VmStatus.STOPPED, CloudProvider.AWS));
        types("a", "m5.large");
        assertThat(service.preview(ENV, GROUP, null, 10_000).hours()).isEqualTo(720);
        assertThat(service.preview(ENV, GROUP, null, 0).hours()).isEqualTo(1);
        assertThat(service.preview(ENV, GROUP, null, null).hours()).isEqualTo(8);

        when(leases.isEnabledFor(environment)).thenReturn(true);
        when(leases.effectiveDefault(environment)).thenReturn(150);
        assertThat(service.preview(ENV, GROUP, null, null).hours()).isEqualTo(3);
    }

    @Test
    void anEksNodeGroupCostsItsNodesTimesTheNodeRate() {
        Vm nodes = vm("ng", VmStatus.STOPPED, CloudProvider.AWS_EKS);
        inGroup(nodes);
        types();
        when(eks.resolveEksNodeInfo(nodes)).thenReturn(new EstimatedCostProvider.EksNodeInfo("m5.large", 3));

        CostPreviewDTO preview = service.preview(ENV, GROUP, null, 1);
        assertThat(preview.vms().get(0).nodeCount()).isEqualTo(3);
        assertThat(preview.hourlyTotal()).isEqualByComparingTo("0.29");
        assertThat(preview.estimatedTotal()).isEqualByComparingTo("0.29");
    }

    @Test
    void anExtensionPricesTheRunningVms() {
        inGroup(vm("a", VmStatus.RUNNING, CloudProvider.AWS), vm("b", VmStatus.STOPPED, CloudProvider.AWS));
        types("a", "m5.large", "b", "m5.large");
        CostPreviewDTO preview = service.preview(ENV, GROUP, null, 2, true);
        assertThat(preview.vms()).extracting(CostPreviewDTO.VmLine::vmId).containsExactly("a");
        assertThat(preview.estimatedTotal()).isEqualByComparingTo("0.19");
    }
}
