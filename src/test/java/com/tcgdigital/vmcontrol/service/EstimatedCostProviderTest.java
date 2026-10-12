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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // Lenient: the environment-fee tests read no repository.
        org.mockito.Mockito.lenient().when(vmVolumeSnapshotRepository.sumSizeGibByVmIds(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(vmStateHistoryRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(vmStateHistoryRepository.findLatestBeforeForVms(any(), any()))
                .thenReturn(List.of());
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

    // --- Spot priced at its Spot rate (E31-T02) ---

    @Test
    void aSpotNodeGroupIsPricedAtTheSpotRateWithItsOnDemandEquivalent() {
        Vm vm = eksNodeGroupVm("{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"m6i.large\"}");
        vm.setPurchaseOption(com.tcgdigital.vmcontrol.model.PurchaseOption.SPOT);
        SpotPriceService spot = org.mockito.Mockito.mock(SpotPriceService.class);
        when(spot.lookupSpotRate("m6i.large", "us-east-1", null)).thenReturn(java.util.Optional.of(new BigDecimal("0.04")));
        provider.setSpotPriceService(spot);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("eks-vm-1");

        assertTrue(estimate.costKnown());
        // 3 x 0.04 Spot = 0.12/hr; 10 h -> 1.20; on demand 3 x 0.096 x 10 = 2.88
        assertEquals(0, new BigDecimal("0.12").compareTo(estimate.hourlyRate()));
        assertEquals(0, new BigDecimal("1.20").compareTo(estimate.cost()));
        assertEquals(0, new BigDecimal("2.88").compareTo(estimate.onDemandEquivalentCost()));
    }

    @Test
    void withoutASpotPriceASpotVmFallsBackToOnDemand() {
        Vm vm = eksNodeGroupVm("{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"t3.large\"}");
        vm.setPurchaseOption(com.tcgdigital.vmcontrol.model.PurchaseOption.SPOT);
        SpotPriceService spot = org.mockito.Mockito.mock(SpotPriceService.class);
        when(spot.lookupSpotRate(any(), any(), any())).thenReturn(java.util.Optional.empty());
        provider.setSpotPriceService(spot);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("eks-vm-1");

        assertEquals(0, new BigDecimal("0.2496").compareTo(estimate.hourlyRate()));
        assertEquals(null, estimate.onDemandEquivalentCost());
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

    // --- EKS node disks and the control-plane fee (E26-T03) ---

    @Test
    void eksNodeDisksArePricedPerNodeForTheHoursTheNodesRun() {
        Vm vm = eksNodeGroupVm("{\"desiredSize\":3,\"instanceType\":\"t3.large\",\"diskSizeGib\":20}");

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("eks-vm-1");

        assertEquals(60, estimate.storageGib());
        // 10 running hours: 0.2496/h x 10 for the nodes, plus 60 GiB for 10/24 of a day.
        BigDecimal disks = pricing.getStorageGbMonthRate().multiply(new BigDecimal("0.416667"))
                .divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(60));
        assertEquals(0, new BigDecimal("2.496").add(disks).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(estimate.cost()));
        assertTrue(disks.signum() > 0);
    }

    @Test
    void aStoppedEksNodeGroupPaysForNoDisks() {
        Vm vm = eksNodeGroupVm("{\"desiredSize\":3,\"instanceType\":\"t3.large\",\"diskSizeGib\":20}");
        vm.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("eks-vm-1");

        assertEquals(0, BigDecimal.ZERO.compareTo(estimate.cost()));
    }

    private Vm inEnvironment(Vm vm, String environmentId) {
        com.tcgdigital.vmcontrol.model.Environment env = new com.tcgdigital.vmcontrol.model.Environment();
        env.setEnvironmentId(environmentId);
        com.tcgdigital.vmcontrol.model.VmGroup group = new com.tcgdigital.vmcontrol.model.VmGroup();
        group.setEnvironment(env);
        vm.setGroup(group);
        return vm;
    }

    @Test
    void theControlPlaneFeeIsChargedOncePerEksEnvironmentWhetherOrNotNodesRun() {
        Vm workers = inEnvironment(eksNodeGroupVm("{\"desiredSize\":3,\"instanceType\":\"t3.large\"}"), "env-eks");
        Vm batch = inEnvironment(eksNodeGroupVm("{\"desiredSize\":1,\"instanceType\":\"t3.large\"}"), "env-eks");
        batch.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
        Vm ec2 = inEnvironment(ec2Vm("vm-ec2", Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS))), "env-ec2");

        Map<String, BigDecimal> fees = provider.environmentFees(List.of(workers, batch, ec2), windowStart, windowEnd);

        // 10 hours at the standard $0.10 per cluster-hour, once for the environment.
        assertEquals(Map.of("env-eks", new BigDecimal("1.00")), fees);
    }

    @Test
    void theControlPlaneFeeStartsWhenTheFirstNodeGroupWasRegistered() {
        Vm vm = inEnvironment(eksNodeGroupVm("{\"desiredSize\":1}"), "env-eks");
        vm.setCreatedAt(Timestamp.from(windowStart.toInstant().plus(5, ChronoUnit.HOURS)));
        org.springframework.test.util.ReflectionTestUtils.setField(pricing, "eksControlPlaneHourlyRate", new BigDecimal("0.60"));

        Map<String, BigDecimal> fees = provider.environmentFees(List.of(vm), windowStart, windowEnd);

        assertEquals(0, new BigDecimal("3.00").compareTo(fees.get("env-eks"))); // extended support: 5 h x $0.60
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
            public Timestamp getInstanceTypeChangedAt() { return null; }
            public Integer getMemoryMib() { return null; }
            public String getArchitecture() { return null; }
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

    // --- One seed query for the whole batch (E08-T04, M12) ---

    @Test
    void seedStatesComeFromOneBatchedQueryNotOnePerVm() {
        Vm a = ec2Vm("vm-a", Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        Vm b = ec2Vm("vm-b", Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        instanceType("vm-a", "t3.large");
        com.tcgdigital.vmcontrol.model.VmStateHistory aWasRunning = com.tcgdigital.vmcontrol.model.VmStateHistory.builder()
                .newStatus(com.tcgdigital.vmcontrol.model.VmStatus.RUNNING).build();
        aWasRunning.setVm(a);
        when(vmStateHistoryRepository.findLatestBeforeForVms(any(), any())).thenReturn(List.of(aWasRunning));

        Map<String, CostDataProvider.VmCostEstimate> result = provider.estimateCosts(List.of(a, b), windowStart, windowEnd);

        org.mockito.Mockito.verify(vmStateHistoryRepository, org.mockito.Mockito.times(1)).findLatestBeforeForVms(any(), any());
        org.mockito.Mockito.verify(vmStateHistoryRepository, org.mockito.Mockito.never())
                .findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc(any(), any());
        // vm-a was RUNNING before the window (seed from the batch) and ran all 10 hours;
        // vm-b has no history, so its current status (STOPPED) is the seed.
        assertEquals(0, new BigDecimal("10.000").compareTo(result.get("vm-a").runtimeHours()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.get("vm-b").runtimeHours()));
    }

    // --- Another region's rate is flagged as approximate (E08-T07) ---

    @Test
    void anOtherRegionRateIsMarkedApproximate() {
        Vm exact = ec2Vm("vm-exact", Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        instanceType("vm-exact", "t3.large");
        assertEquals(false, provider.estimateCosts(List.of(exact), windowStart, windowEnd).get("vm-exact").priceApproximate());

        Vm far = ec2Vm("vm-far", Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        far.setRegion("sa-east-1"); // not in the pricing file
        instanceType("vm-far", "t3.large");
        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(far), windowStart, windowEnd).get("vm-far");
        assertTrue(estimate.costKnown());
        assertTrue(estimate.priceApproximate());
    }

    // --- RDS (E33-T05) ---

    private Vm rdsVm(String vmId, String providerVmId) {
        Vm vm = new Vm(vmId);
        vm.setProvider(CloudProvider.AWS_RDS);
        vm.setProviderVmId(providerVmId);
        vm.setRegion("us-east-1");
        vm.setCreatedAt(Timestamp.from(windowStart.toInstant().minus(10, ChronoUnit.DAYS)));
        vm.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
        return vm;
    }

    private void rdsInventory(Vm vm, String dbClass, String engine, boolean multiAz, Integer storageGib) {
        instanceType(vm.getVmId(), dbClass);
        com.tcgdigital.vmcontrol.model.VmInventorySnapshot s = new com.tcgdigital.vmcontrol.model.VmInventorySnapshot();
        s.setVm(vm);
        s.setInstanceType(dbClass);
        s.setEngine(engine);
        s.setMultiAz(multiAz);
        s.setTotalStorageGib(storageGib);
        when(vmInventorySnapshotRepository.findByVmVmIdIn(any())).thenReturn(List.of(s));
    }

    @Test
    void aDatabaseStoppedHalfTheWindowPaysComputeForHalfAndStorageForAll() {
        Vm vm = rdsVm("vm-db", "db:orders");
        rdsInventory(vm, "db.t4g.medium", "postgres", false, 100);
        com.tcgdigital.vmcontrol.model.VmStateHistory stopped = com.tcgdigital.vmcontrol.model.VmStateHistory.builder()
                .previousStatus(com.tcgdigital.vmcontrol.model.VmStatus.RUNNING)
                .newStatus(com.tcgdigital.vmcontrol.model.VmStatus.STOPPED).build();
        stopped.setChangedAt(Timestamp.from(windowStart.toInstant().plus(5, ChronoUnit.HOURS)));
        stopped.setVm(vm);
        when(vmStateHistoryRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(any(), any(), any()))
                .thenReturn(List.of(stopped));

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-db");

        assertTrue(estimate.costKnown());
        assertFalse(estimate.priceApproximate());
        assertEquals(0, new BigDecimal("0.065").compareTo(estimate.hourlyRate()));
        assertEquals(0, new BigDecimal("5").compareTo(estimate.runtimeHours()));
        assertEquals(100L, estimate.storageGib());
        // 5 h x 0.065 = 0.325 compute + 100 GiB x 0.115 for the whole 10 h (0.416667 day) = 0.159722 storage
        BigDecimal storage = new BigDecimal("0.115").multiply(new BigDecimal("0.416667"))
                .divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        assertEquals(0, new BigDecimal("0.325").add(storage).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(estimate.cost()));
    }

    @Test
    void aMultiAzDatabaseUsesTheMultiAzRateAndDoubleStorage() {
        Vm vm = rdsVm("vm-db", "db:orders");
        vm.setStatus(com.tcgdigital.vmcontrol.model.VmStatus.RUNNING);
        rdsInventory(vm, "db.m5.large", "mysql", true, 50);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-db");

        assertEquals(0, new BigDecimal("0.342").compareTo(estimate.hourlyRate()));
        BigDecimal storage = new BigDecimal("0.230").multiply(new BigDecimal("0.416667"))
                .divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(50));
        assertEquals(0, new BigDecimal("3.42").add(storage).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(estimate.cost()));
    }

    @Test
    void anUnpricedEngineIsUnknownButStillPaysStorage() {
        Vm vm = rdsVm("vm-db", "db:legacy");
        rdsInventory(vm, "db.m5.large", "sqlserver-se", false, 200);

        CostDataProvider.VmCostEstimate estimate = provider.estimateCosts(List.of(vm), windowStart, windowEnd).get("vm-db");

        assertFalse(estimate.costKnown());
        assertTrue(estimate.cost().signum() > 0);
    }
}
