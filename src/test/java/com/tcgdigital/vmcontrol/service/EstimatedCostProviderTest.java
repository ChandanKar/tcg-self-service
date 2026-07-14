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
    private Timestamp windowStart;
    private Timestamp windowEnd;

    @BeforeEach
    void setUp() {
        PricingReferenceService pricingReferenceService = new PricingReferenceService();
        pricingReferenceService.loadPricing();

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
}
