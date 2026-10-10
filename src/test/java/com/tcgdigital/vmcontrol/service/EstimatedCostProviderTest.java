package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EKS node groups are stored as one Vm per group (not per node), with no VmInventorySnapshot —
 * these tests cover the EKS-specific pricing path in EstimatedCostProvider: instanceType/node
 * count read from Vm.metadata, priced as plain "AWS" (nodes are real EC2 hardware), multiplied
 * by node count.
 */
@ExtendWith(MockitoExtension.class)
class EstimatedCostProviderTest {

    @Mock
    private VmVolumeSnapshotRepository vmVolumeSnapshotRepository;
    @Mock
    private VmStateHistoryRepository vmStateHistoryRepository;
    @Mock
    private VmInventorySnapshotRepository vmInventorySnapshotRepository;

    private EstimatedCostProvider provider;
    private PricingReferenceService pricing;
    private Timestamp windowStart;
    private Timestamp windowEnd;

    @BeforeEach
    void setUp() {
        PricingReferenceService pricingReferenceService = new PricingReferenceService();
        pricingReferenceService.loadPricing();
        pricing = pricingReferenceService;

        provider = new EstimatedCostProvider(
                pricingReferenceService,
                new VmCostCalculator(),
                vmVolumeSnapshotRepository,
                vmStateHistoryRepository,
                vmInventorySnapshotRepository,
                new ObjectMapper());

        Instant now = Instant.now();
        windowStart = Timestamp.from(now.minus(20, ChronoUnit.HOURS));
        windowEnd = Timestamp.from(now.minus(10, ChronoUnit.HOURS));

        when(vmVolumeSnapshotRepository.sumSizeGibByVmIds(any())).thenReturn(List.of());
        when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(any())).thenReturn(List.of());
        when(vmStateHistoryRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(vmStateHistoryRepository.findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    private Vm eksNodeGroupVm(String metadataJson) {
        Vm vm = new Vm("eks-vm-1");
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setRegion("us-east-1");
        vm.setCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));
        vm.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.RUNNING);
        vm.setMetadata(metadataJson);
        return vm;
    }

    @Test
    void eksNodeGroupCostIsPerNodeRateTimesNodeCount() {
        Vm vm = eksNodeGroupVm("{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"t3.large\"}");

        Map<String, CostDataProvider.VmCostEstimate> result =
                provider.estimateCosts(List.of(vm), windowStart, windowEnd);

        CostDataProvider.VmCostEstimate estimate = result.get("eks-vm-1");
        assertTrue(estimate.costKnown(), "t3.large in us-east-1 is in the pricing reference — cost should be known");
        // t3.large @ us-east-1 = 0.0832/hr per node x 3 nodes = 0.2496/hr effective
        assertEquals(0, new BigDecimal("0.2496").compareTo(estimate.hourlyRate()));
        // 10h window, RUNNING throughout: 0.2496 x 10 = 2.496 -> 2.50
        assertEquals(0, new BigDecimal("2.50").compareTo(estimate.cost()));
    }

    @Test
    void eksNodeGroupWithoutMetadataIsUnknownNotZero() {
        Vm vm = eksNodeGroupVm(null);

        Map<String, CostDataProvider.VmCostEstimate> result =
                provider.estimateCosts(List.of(vm), windowStart, windowEnd);

        CostDataProvider.VmCostEstimate estimate = result.get("eks-vm-1");
        assertEquals(false, estimate.costKnown(), "no instance type known — must be flagged unknown, not silently $0");
    }

    @Test
    void eksNodeGroupMissingDesiredSizeDefaultsToOneNode() {
        Vm vm = eksNodeGroupVm("{\"minSize\":1,\"instanceType\":\"t3.large\"}");

        Map<String, CostDataProvider.VmCostEstimate> result =
                provider.estimateCosts(List.of(vm), windowStart, windowEnd);

        CostDataProvider.VmCostEstimate estimate = result.get("eks-vm-1");
        assertEquals(0, new BigDecimal("0.0832").compareTo(estimate.hourlyRate()));
    }

    // --- Storage limited to the VM's lifetime (E08-T01) ---

    private Vm ec2Vm(String id, Timestamp createdAt) {
        Vm vm = new Vm(id);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("us-east-1");
        vm.setCreatedAt(createdAt);
        vm.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
        return vm;
    }

    private void storage(String vmId, long gib) {
        VmVolumeSnapshotRepository.VolumeSizeTotal total = new VmVolumeSnapshotRepository.VolumeSizeTotal() {
            public String getVmId() { return vmId; }
            public Long getTotalSizeGib() { return gib; }
        };
        when(vmVolumeSnapshotRepository.sumSizeGibByVmIds(any())).thenReturn(List.of(total));
    }

    private void instanceType(String vmId, String type) {
        VmInventorySnapshotRepository.InstanceTypeProjection p = new VmInventorySnapshotRepository.InstanceTypeProjection() {
            public String getVmId() { return vmId; }
            public String getInstanceType() { return type; }
        };
        when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(any())).thenReturn(List.of(p));
    }

    /** Cost of {@code gib} GiB of storage for {@code days} days, rounded as the provider rounds it. */
    private BigDecimal storageCost(long gib, String days) {
        return pricing.getStorageGbMonthRate().multiply(new BigDecimal(days))
                .divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(gib)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Test
    void aStoppedVmCreatedMidWindowPaysStorageOnlySinceCreation() {
        // 10-hour window; the VM was created 5 hours into it, then stayed stopped.
        Vm vm = ec2Vm("vm-new", Timestamp.from(windowStart.toInstant().plus(5, ChronoUnit.HOURS)));
        storage("vm-new", 300);
        instanceType("vm-new", "t3.large");

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-new");

        assertTrue(estimate.costKnown());
        // 5 hours = 0.208333 day of storage, not the window's 0.416667.
        assertEquals(0, storageCost(300, "0.208333").compareTo(estimate.cost()));
    }

    @Test
    void anUnpricedVmsStorageOnlyCostIsProratedToo() {
        Vm vm = ec2Vm("vm-unpriced", Timestamp.from(windowStart.toInstant().plus(5, ChronoUnit.HOURS)));
        storage("vm-unpriced", 300);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-unpriced");

        assertEquals(false, estimate.costKnown());
        assertEquals(0, storageCost(300, "0.208333").compareTo(estimate.cost()));
    }

    @Test
    void aVmOlderThanTheWindowPaysStorageForTheWholeWindow() {
        Vm vm = ec2Vm("vm-old", Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        storage("vm-old", 300);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-old");

        assertEquals(0, storageCost(300, "0.416667").compareTo(estimate.cost()));
    }
}
