package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricDailyRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Focused on {@link CostEstimationService#getRightsizingCandidates} and its Compute Optimizer
 * overlay (Cost Management Phase 3) — the piece most at risk of silently changing behavior.
 * Other views (summary, breakdowns, idle waste) are exercised indirectly via
 * {@link EstimatedCostProviderTest} and live verification, not duplicated here.
 */
@ExtendWith(MockitoExtension.class)
class CostEstimationServiceTest {

    private static final String VM_ID = "vm-1";
    private static final String INSTANCE_ID = "i-0123456789abcdef0";
    private static final String REGION = "us-east-1";

    @Mock private VmRepository vmRepository;
    @Mock private VmIdleSummaryRepository vmIdleSummaryRepository;
    @Mock private VmMetricDailyRepository vmMetricDailyRepository;
    @Mock private VmMetricSampleRepository vmMetricSampleRepository;
    @Mock private VmInventorySnapshotRepository vmInventorySnapshotRepository;
    @Mock private CostDailySnapshotRepository costDailySnapshotRepository;
    @Mock private CostDataProvider costDataProvider;
    @Mock private ComputeOptimizerService computeOptimizerService;
    @Mock private VmMetricDailyRepository.UtilizationStats cpuStats;
    @Mock private VmMetricDaily vmMetricDaily;
    @Mock private VmInventorySnapshotRepository.InstanceTypeProjection instanceTypeProjection;

    private CostEstimationService service;

    @BeforeEach
    void setUp() {
        PricingReferenceService pricingReferenceService = new PricingReferenceService();
        pricingReferenceService.loadPricing();

        service = new CostEstimationService(vmRepository, vmIdleSummaryRepository, vmMetricDailyRepository,
                vmMetricSampleRepository, vmInventorySnapshotRepository, costDailySnapshotRepository, costDataProvider,
                pricingReferenceService, new VmCostCalculator(), new TeamResolver(new com.fasterxml.jackson.databind.ObjectMapper()),
                computeOptimizerService);

        // Field-injected @Value thresholds aren't populated outside a Spring context — set them
        // directly, same pattern AwsCloudProviderServiceTest already uses for its own @Value fields.
        ReflectionTestUtils.setField(service, "scaleDownCpuThreshold", BigDecimal.valueOf(15));
        ReflectionTestUtils.setField(service, "scaleDownConsecutiveDays", 3);
        ReflectionTestUtils.setField(service, "scaleUpCpuThreshold", BigDecimal.valueOf(70));
        ReflectionTestUtils.setField(service, "scaleUpConsecutiveMinutes", 15);

        Vm vm = buildAwsVm();
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm));

        when(cpuStats.getVmId()).thenReturn(VM_ID);
        when(cpuStats.getAvgCpu()).thenReturn(BigDecimal.valueOf(2));
        when(cpuStats.getMaxCpu()).thenReturn(BigDecimal.valueOf(10));
        when(vmMetricDailyRepository.findUtilizationStatsSince(anyList(), any())).thenReturn(List.of(cpuStats));

        // Scale-down candidacy: 3 consecutive daily rows (matching scaleDownConsecutiveDays),
        // each below the 15% threshold, so every test in this class exercises a scale-down
        // candidate — this class's focus is the Compute Optimizer overlay (per the class
        // javadoc), not the candidacy rule itself. lenient() since the scale-up test below
        // overrides the daily-rows stub and never touches these two getters.
        org.mockito.Mockito.lenient().when(vmMetricDaily.getVm()).thenReturn(vm);
        org.mockito.Mockito.lenient().when(vmMetricDaily.getAvgCpuUtilization()).thenReturn(BigDecimal.valueOf(2));
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(List.of(vmMetricDaily, vmMetricDaily, vmMetricDaily));

        when(vmIdleSummaryRepository.findByVmVmIdIn(anyList())).thenReturn(List.of());

        org.mockito.Mockito.lenient().when(instanceTypeProjection.getVmId()).thenReturn(VM_ID);
        org.mockito.Mockito.lenient().when(instanceTypeProjection.getInstanceType()).thenReturn("t3.xlarge");
        when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(anyList())).thenReturn(List.of(instanceTypeProjection));

        CostDataProvider.VmCostEstimate estimate = new CostDataProvider.VmCostEstimate(
                true, BigDecimal.valueOf(0.1664), BigDecimal.valueOf(720), 0, BigDecimal.valueOf(119.81));
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(VM_ID, estimate));
    }

    @Test
    void fallsBackToCpuThresholdRuleWhenComputeOptimizerHasNoOpinion() {
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of());

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("cpu-threshold-rule", row.source());
        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(null, row.findingLevel());
    }

    @Test
    void prefersComputeOptimizerRecommendationWhenOneExists() {
        ComputeOptimizerService.Recommendation recommendation =
                new ComputeOptimizerService.Recommendation(INSTANCE_ID, "OVER_PROVISIONED", List.of("m6a.large"));
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of(INSTANCE_ID, recommendation));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("compute-optimizer", row.source());
        assertEquals("m6a.large", row.suggestedInstanceType());
        assertEquals("OVER_PROVISIONED", row.findingLevel());
    }

    @Test
    void flagsScaleUpCandidateWhenCpuStaysAboveThresholdAcrossAllSamples() {
        // Override setUp's scale-down data so this VM only qualifies via the scale-up path.
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(List.of());

        Vm vm = buildAwsVm();
        com.tcgdigital.vmcontrol.model.VmMetricSample sample = org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.model.VmMetricSample.class);
        when(sample.getVm()).thenReturn(vm);
        when(sample.getCpuUtilization()).thenReturn(BigDecimal.valueOf(85));
        when(vmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any()))
                .thenReturn(List.of(sample, sample));

        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of());

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_UP", row.direction());
        assertEquals("cpu-threshold-rule", row.source());
        assertEquals("t3.2xlarge", row.suggestedInstanceType());
    }

    @Test
    void callsComputeOptimizerOncePerDistinctRegionNotPerVm() {
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of());

        onlyCandidate();

        org.mockito.Mockito.verify(computeOptimizerService, org.mockito.Mockito.times(1)).getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION));
    }

    @Test
    void spendByAccountSplitsTheEstimateAcrossAccounts() {
        // E28-T11
        Vm other = buildAwsVm();
        other.setVmId("vm-2");
        other.setAwsAccountRef("acct-b");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(buildAwsVm(), other));
        CostDataProvider.VmCostEstimate estimate = new CostDataProvider.VmCostEstimate(
                true, BigDecimal.valueOf(0.1664), BigDecimal.valueOf(720), 0, BigDecimal.valueOf(119.81));
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(VM_ID, estimate, "vm-2", estimate));
        org.mockito.Mockito.lenient().when(computeOptimizerService.getAllRecommendations(
                org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class))).thenReturn(Map.of());

        List<com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO> rows = service.getSpendByAccount();

        assertEquals(2, rows.size());
        assertEquals(java.util.Set.of(com.tcgdigital.vmcontrol.model.AwsAccount.DEFAULT_REF, "acct-b"),
                rows.stream().map(com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO::dimensionKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, new BigDecimal("239.62").compareTo(rows.stream().map(com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test
    void callsComputeOptimizerOncePerAccountAndRegion() {
        // E28-T06: two VMs in one region but different accounts -> two calls, each with its own account.
        Vm other = buildAwsVm();
        other.setVmId("vm-2");
        other.setAwsAccountRef("acct-b");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(buildAwsVm(), other));
        CostDataProvider.VmCostEstimate estimate = new CostDataProvider.VmCostEstimate(
                true, BigDecimal.valueOf(0.1664), BigDecimal.valueOf(720), 0, BigDecimal.valueOf(119.81));
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(VM_ID, estimate, "vm-2", estimate));
        when(computeOptimizerService.getAllRecommendations(org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class)))
                .thenReturn(Map.of());

        service.getRightsizingCandidates(PageRequest.of(0, 25));

        org.mockito.Mockito.verify(computeOptimizerService).getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION));
        org.mockito.Mockito.verify(computeOptimizerService).getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of("acct-b", REGION));
    }

    /** A 3-node t3.xlarge EKS node group in place of the EC2 VM (E26-T02). */
    private Vm eksNodeGroup() {
        Vm vm = buildAwsVm();
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId("prod/workers");
        vm.setMetadata("{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"t3.xlarge\"}");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm));
        org.mockito.Mockito.lenient().when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(anyList())).thenReturn(List.of());
        // EstimatedCostProvider prices the group as 3 nodes.
        CostDataProvider.VmCostEstimate estimate = new CostDataProvider.VmCostEstimate(
                true, BigDecimal.valueOf(0.1664 * 3), BigDecimal.valueOf(720), 0, BigDecimal.valueOf(359.42));
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(VM_ID, estimate));
        return vm;
    }

    @Test
    void anEksNodeGroupIsARightsizingCandidateWithSavingsForEveryNode() {
        eksNodeGroup();
        RightsizingCandidateDTO row = onlyCandidate();
        assertEquals("t3.xlarge", row.currentInstanceType());
        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(Integer.valueOf(3), row.nodeCount());
        BigDecimal oneNodeAfter = new PricingReferenceService() {{ loadPricing(); }}
                .lookupHourlyRate("AWS", "t3.large", REGION).hourlyRate().multiply(BigDecimal.valueOf(720));
        assertEquals(0, oneNodeAfter.multiply(BigDecimal.valueOf(3)).setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(row.estimatedMonthlyCostAfter()), "the after cost covers all 3 nodes");
        assertEquals(0, BigDecimal.valueOf(359.42).subtract(row.estimatedMonthlyCostAfter()).compareTo(row.estimatedMonthlySavings()));
    }

    @Test
    void anIdleEksNodeGroupIsOneRowWithItsNodeCountAndEveryNodesIdleCost() {
        Vm vm = eksNodeGroup();
        com.tcgdigital.vmcontrol.model.VmIdleSummary idle = new com.tcgdigital.vmcontrol.model.VmIdleSummary();
        idle.setVm(vm);
        idle.setIdle(true);
        idle.setIdleDurationMinutes(600);
        when(vmIdleSummaryRepository.findByVmVmIdIn(anyList())).thenReturn(List.of(idle));

        List<com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO> rows = service.getIdleWaste(PageRequest.of(0, 25)).getContent();
        assertEquals(1, rows.size());
        assertEquals(Integer.valueOf(3), rows.get(0).nodeCount());
        // 3 nodes x $0.1664/h x 10 h idle
        assertEquals(0, new BigDecimal("4.99").compareTo(rows.get(0).monthlyIdleCost()));
    }

    // ---- Memory-aware rightsizing (E30-T03) ----

    /** setUp's three low-CPU days, now with the CloudWatch agent reporting this daily memory peak. */
    private void memoryPeakEachDay(double peak) {
        when(vmMetricDaily.getMemorySampleCount()).thenReturn(288);
        when(vmMetricDaily.getMaxMemoryUtilization()).thenReturn(BigDecimal.valueOf(peak));
    }

    @Test
    void lowCpuWithHighMemoryPeaksIsNotAScaleDownCandidate() {
        memoryPeakEachDay(80);

        assertEquals(0, service.getRightsizingCandidates(PageRequest.of(0, 25)).getTotalElements());
    }

    @Test
    void lowCpuWithoutTheAgentIsACandidateMarkedMemoryUnknown() {
        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(List.of("CPU_LOW", "MEMORY_UNKNOWN"), row.reasons());
        assertEquals(false, row.memoryDataAvailable());
        assertEquals(null, row.peakMemoryUtilization());
    }

    @Test
    void lowCpuAndLowMemoryThatFitsTheSmallerTypeKeepsTheSuggestion() {
        memoryPeakEachDay(30);
        when(cpuStats.getAvgMemory()).thenReturn(BigDecimal.valueOf(20));
        when(cpuStats.getMaxMemory()).thenReturn(BigDecimal.valueOf(30)); // 16 GiB x 30% x 1.2 = 5.9 GiB <= 8 GiB

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(List.of("CPU_LOW", "MEMORY_LOW"), row.reasons());
        assertEquals(true, row.memoryDataAvailable());
        assertEquals(0, BigDecimal.valueOf(30).compareTo(row.peakMemoryUtilization()));
    }

    @Test
    void aSmallerTypeThatWouldNotHoldTheMemoryPeakIsDroppedButTheRowStays() {
        memoryPeakEachDay(55);
        when(cpuStats.getMaxMemory()).thenReturn(BigDecimal.valueOf(55)); // 16 GiB x 55% x 1.2 = 10.6 GiB > 8 GiB

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_DOWN", row.direction());
        assertEquals(null, row.suggestedInstanceType());
        assertEquals(false, row.costKnown());
        assertEquals(List.of("CPU_LOW", "MEMORY_LOW", "MEMORY_WOULD_NOT_FIT"), row.reasons());
        // not counted as a saving
        assertEquals(0, service.getSummary().rightsizingCandidateCount());
    }

    @Test
    void inventoryMemoryWinsOverTheTypesSpecForTheFitCheck() {
        memoryPeakEachDay(55);
        when(cpuStats.getMaxMemory()).thenReturn(BigDecimal.valueOf(55));
        when(instanceTypeProjection.getMemoryMib()).thenReturn(8192); // 8 GiB x 55% x 1.2 = 5.3 GiB <= 8 GiB

        assertEquals("t3.large", onlyCandidate().suggestedInstanceType());
    }

    @Test
    void memoryAboveTheThresholdInEverySampleIsAScaleUp() {
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(List.of());
        Vm vm = buildAwsVm();
        com.tcgdigital.vmcontrol.model.VmMetricSample sample = org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.model.VmMetricSample.class);
        when(sample.getVm()).thenReturn(vm);
        when(sample.getCpuUtilization()).thenReturn(BigDecimal.valueOf(30));
        when(sample.getMemoryUtilization()).thenReturn(BigDecimal.valueOf(92));
        when(vmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any()))
                .thenReturn(List.of(sample, sample));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_UP", row.direction());
        assertEquals("t3.2xlarge", row.suggestedInstanceType());
        assertEquals(List.of("MEMORY_HIGH"), row.reasons());
    }

    @Test
    void withMemoryRightsizingOffTheResultsAreTheCpuOnlyOnes() {
        ReflectionTestUtils.setField(service, "memoryRightsizingEnabled", false);
        org.mockito.Mockito.lenient().when(vmMetricDaily.getMemorySampleCount()).thenReturn(288);
        org.mockito.Mockito.lenient().when(vmMetricDaily.getMaxMemoryUtilization()).thenReturn(BigDecimal.valueOf(95));
        when(cpuStats.getMaxMemory()).thenReturn(BigDecimal.valueOf(95));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(List.of("CPU_LOW"), row.reasons());
    }

    // ---- One recommendation: modernize, alternatives, agreement (E30-T05) ----

    /** No rule candidacy: no daily rows, no samples. */
    private void quietVm(String instanceType) {
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(List.of());
        when(instanceTypeProjection.getInstanceType()).thenReturn(instanceType);
    }

    private static ComputeOptimizerService.Recommendation co(String finding, ComputeOptimizerService.Option... options) {
        List<ComputeOptimizerService.Option> list = List.of(options);
        return new ComputeOptimizerService.Recommendation(INSTANCE_ID, finding,
                list.stream().map(ComputeOptimizerService.Option::instanceType).toList(), list, List.of(), true);
    }

    private static ComputeOptimizerService.Option coOption(int rank, String type, String risk, String... differences) {
        return new ComputeOptimizerService.Option(rank, type, new BigDecimal(risk), "Low", List.of(differences), null);
    }

    @Test
    void anOlderGenerationVmIsModernizedWithAGravitonAlternativeThatCannotBeApplied() {
        quietVm("m5.large");

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("MODERNIZE", row.direction());
        // m7i.large costs more per hour than m5.large in us-east-1, so the newest generation that
        // is not dearer is chosen
        assertEquals("m6a.large", row.suggestedInstanceType());
        assertEquals("newer-generation", row.source());
        assertEquals(List.of("NEWER_GENERATION"), row.reasons());
        assertEquals(true, row.applicable());
        assertEquals("ONLY_RULE", row.agreement());
        var byType = row.alternatives().stream().collect(java.util.stream.Collectors.toMap(
                com.tcgdigital.vmcontrol.dto.RightsizingOptionDTO::instanceType, o -> o));
        assertEquals("NEWER_GENERATION", byType.get("m6a.large").kind());
        assertEquals("NEWER_GENERATION", byType.get("m7i.large").kind());
        assertEquals(true, byType.get("m7i.large").applicable());
        var graviton = byType.get("m7g.large");
        assertEquals("GRAVITON", graviton.kind());
        assertEquals(true, graviton.requiresArchitectureChange());
        assertEquals(false, graviton.applicable());
        assertEquals("Requires an ARM64 image", graviton.applicableReason());
        assertEquals("arm64", graviton.architecture());
        assertEquals(true, row.canApply("m7i.large"));
        assertEquals(false, row.canApply("m7g.large"));
    }

    @Test
    void aCurrentGenerationVmWithNothingToSayIsNotListed() {
        quietVm("m7i.large");

        assertEquals(0, service.getRightsizingCandidates(PageRequest.of(0, 25)).getTotalElements());
    }

    @Test
    void theDirectionFilterKeepsOnlyThatDirection() {
        quietVm("m5.large");

        assertEquals(1, service.getRightsizingCandidates("MODERNIZE", PageRequest.of(0, 25)).getTotalElements());
        assertEquals(0, service.getRightsizingCandidates("SCALE_DOWN", PageRequest.of(0, 25)).getTotalElements());
        // KPIs do not count a modernize row as a scale-up or a saving
        assertEquals(0, service.getSummary().scaleUpCandidateCount());
        assertEquals(0, service.getSummary().rightsizingCandidateCount());
    }

    @Test
    void computeOptimizerFindingOptimizedWhileTheRuleSaysScaleDownIsADisagreement() {
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION)))
                .thenReturn(Map.of(INSTANCE_ID, co("OPTIMIZED")));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_DOWN", row.direction());
        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals("DISAGREE", row.agreement());
    }

    @Test
    void theSameSuggestionFromBothIsAnAgreementAndOptionsCarryTheirRisk() {
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION)))
                .thenReturn(Map.of(INSTANCE_ID, co("OVER_PROVISIONED",
                        coOption(1, "t3.large", "1.50"), coOption(2, "t4g.large", "2.00", "Architecture"))));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("compute-optimizer", row.source());
        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals("AGREE", row.agreement());
        var chosen = row.alternatives().get(0);
        assertEquals("t3.large", chosen.instanceType());
        assertEquals("compute-optimizer", chosen.source());
        var graviton = row.alternatives().stream().filter(o -> o.instanceType().equals("t4g.large")).findFirst().orElseThrow();
        assertEquals("GRAVITON", graviton.kind());
        assertEquals(false, graviton.applicable());
        // the chosen option keeps Compute Optimizer's own risk
        assertEquals(0, new BigDecimal("1.50").compareTo(chosen.performanceRisk()));
    }

    @Test
    void anInstanceOnlyComputeOptimizerFlagsIsListedAsOnlyAws() {
        quietVm("t3.xlarge");
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION)))
                .thenReturn(Map.of(INSTANCE_ID, co("OVER_PROVISIONED", coOption(1, "t3.large", "1.00"))));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_DOWN", row.direction());
        assertEquals("compute-optimizer", row.source());
        assertEquals("ONLY_AWS", row.agreement());
        assertEquals(List.of("AWS_OVER_PROVISIONED"), row.reasons());
    }

    @Test
    void aGravitonOptionChosenByComputeOptimizerIsNotApplicable() {
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION)))
                .thenReturn(Map.of(INSTANCE_ID, co("OVER_PROVISIONED", coOption(1, "t4g.large", "0.50", "Architecture"))));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("t4g.large", row.suggestedInstanceType());
        assertEquals(false, row.applicable());
        assertEquals("Requires an ARM64 image", row.applicableReason());
        assertEquals(false, row.canApply("t4g.large"));
    }

    private RightsizingCandidateDTO onlyCandidate() {
        List<RightsizingCandidateDTO> content =
                service.getRightsizingCandidates(PageRequest.of(0, 25)).getContent();
        assertEquals(1, content.size());
        return content.get(0);
    }

    private Vm buildAwsVm() {
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setDisplayName("prod-01");

        VmGroup group = new VmGroup();
        group.setGroupId("grp-1");
        group.setEnvironment(env);

        Vm vm = new Vm();
        vm.setVmId(VM_ID);
        vm.setGroup(group);
        vm.setDisplayName("web-01");
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId(INSTANCE_ID);
        vm.setRegion(REGION);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        return vm;
    }

    // ---- Bundle cache and KPI alignment (E08-T04, M12) ----

    @Test
    void theSixEndpointsShareOneBundleBuildWithinTheTtl() {
        service.getSummary();
        service.getSpendByEnvironment();
        service.getSpendByVmType();
        service.getIdleWaste(PageRequest.of(0, 25));
        service.getRightsizingCandidates(PageRequest.of(0, 25));
        service.getVmCostDetail(PageRequest.of(0, 25));

        org.mockito.Mockito.verify(vmRepository, org.mockito.Mockito.times(1)).findByIsActiveTrueFetchGroupAndEnvironment();
    }

    @Test
    void invalidatingForcesARebuild() {
        service.getSummary();
        service.invalidateBundles();
        service.getSummary();

        org.mockito.Mockito.verify(vmRepository, org.mockito.Mockito.times(2)).findByIsActiveTrueFetchGroupAndEnvironment();
    }

    @Test
    void idleRowsCarryTheirJiraTicketFromOneBatchedLookup() {
        com.tcgdigital.vmcontrol.model.VmIdleSummary idle = new com.tcgdigital.vmcontrol.model.VmIdleSummary();
        Vm vm = buildAwsVm();
        idle.setVm(vm);
        idle.setIdle(true);
        idle.setIdleDurationMinutes(600);
        when(vmIdleSummaryRepository.findByVmVmIdIn(anyList())).thenReturn(List.of(idle));
        com.tcgdigital.vmcontrol.repository.ExternalTicketRepository tickets =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.ExternalTicketRepository.class);
        when(tickets.findBySourceTypeAndSystemAndSourceIdIn(org.mockito.ArgumentMatchers.eq(
                com.tcgdigital.vmcontrol.model.ExternalTicket.SourceType.IDLE), org.mockito.ArgumentMatchers.eq("JIRA"), anyList()))
                .thenReturn(List.of(new com.tcgdigital.vmcontrol.model.ExternalTicket("t-1",
                        com.tcgdigital.vmcontrol.model.ExternalTicket.SourceType.IDLE, vm.getVmId(), "JIRA", "OPS-9",
                        "https://acme.atlassian.net/browse/OPS-9", null, null, null)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "externalTicketRepository", tickets);

        com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO row = service.getIdleWaste(PageRequest.of(0, 25)).getContent().get(0);
        assertEquals("OPS-9", row.jiraKey());
        assertEquals("https://acme.atlassian.net/browse/OPS-9", row.jiraUrl());
        org.mockito.Mockito.verify(tickets, org.mockito.Mockito.times(1)).findBySourceTypeAndSystemAndSourceIdIn(any(), any(), anyList());
    }

    @Test
    void idleWasteKpiIsTheSumOfTheTablesMonthlyIdleCost() {
        com.tcgdigital.vmcontrol.model.VmIdleSummary idle = new com.tcgdigital.vmcontrol.model.VmIdleSummary();
        idle.setVm(buildAwsVm());
        idle.setIdle(true);
        idle.setIdleDurationMinutes(600); // 10 h at $0.1664/h = $1.66, not the VM's $119.81 monthly cost
        when(vmIdleSummaryRepository.findByVmVmIdIn(anyList())).thenReturn(List.of(idle));

        com.tcgdigital.vmcontrol.dto.CostSummaryDTO summary = service.getSummary();
        BigDecimal tableTotal = service.getIdleWaste(PageRequest.of(0, 25)).getContent().stream()
                .map(com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO::monthlyIdleCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, new BigDecimal("1.66").compareTo(summary.idleWasteMonthlyCost()));
        assertEquals(0, tableTotal.compareTo(summary.idleWasteMonthlyCost()));
    }

    @Test
    void theEksControlPlaneFeeIsInTheTotalAndItsEnvironmentsBar() {
        BigDecimal vmOnly = service.getSummary().totalMonthlyCost();
        service.invalidateBundles();
        when(costDataProvider.environmentFees(anyList(), any(), any())).thenReturn(Map.of("env-1", new BigDecimal("72.00")));

        com.tcgdigital.vmcontrol.dto.CostSummaryDTO summary = service.getSummary();

        assertEquals(0, new BigDecimal("72.00").compareTo(summary.eksControlPlaneCost()));
        assertEquals(0, vmOnly.add(new BigDecimal("72.00")).compareTo(summary.totalMonthlyCost()));
        assertEquals(0, vmOnly.add(new BigDecimal("72.00")).compareTo(service.getSpendByEnvironment().get(0).cost()));
    }

    @Test
    void rightsizingKpiCountsOnlyMoneySavingScaleDowns() {
        com.tcgdigital.vmcontrol.dto.CostSummaryDTO summary = service.getSummary();
        RightsizingCandidateDTO down = onlyCandidate();

        assertEquals("SCALE_DOWN", down.direction());
        assertEquals(1, summary.rightsizingCandidateCount());
        assertEquals(0, down.estimatedMonthlySavings().compareTo(summary.rightsizingPotentialSavings()));
        assertEquals(0, summary.scaleUpCandidateCount());
    }

    @Test
    void aScaleUpIsCountedSeparatelyAndNotAsASaving() {
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(List.of());
        Vm vm = buildAwsVm();
        com.tcgdigital.vmcontrol.model.VmMetricSample sample = org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.model.VmMetricSample.class);
        when(sample.getVm()).thenReturn(vm);
        when(sample.getCpuUtilization()).thenReturn(BigDecimal.valueOf(85));
        when(vmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any()))
                .thenReturn(List.of(sample, sample));

        com.tcgdigital.vmcontrol.dto.CostSummaryDTO summary = service.getSummary();

        assertEquals(0, summary.rightsizingCandidateCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.rightsizingPotentialSavings()));
        assertEquals(1, summary.scaleUpCandidateCount());
    }

    // ---- Compute Optimizer options must be cheaper; no zero-saving scale-downs (E08-T05, M11) ----

    @Test
    void aComputeOptimizerOptionDearerThanTheCurrentTypeIsSkipped() {
        // t3.xlarge now; the top option (t3.2xlarge) costs more, the next (m6a.large) is cheaper.
        ComputeOptimizerService.Recommendation recommendation = new ComputeOptimizerService.Recommendation(
                INSTANCE_ID, "OVER_PROVISIONED", List.of("t3.2xlarge", "m6a.large"));
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of(INSTANCE_ID, recommendation));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("m6a.large", row.suggestedInstanceType());
        assertEquals("compute-optimizer", row.source());
        org.junit.jupiter.api.Assertions.assertTrue(row.estimatedMonthlySavings().signum() > 0);
    }

    @Test
    void withOnlyDearerOptionsTheRuleBasedSuggestionIsUsed() {
        ComputeOptimizerService.Recommendation recommendation = new ComputeOptimizerService.Recommendation(
                INSTANCE_ID, "OVER_PROVISIONED", List.of("t3.2xlarge"));
        when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of(INSTANCE_ID, recommendation));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("cpu-threshold-rule", row.source());
        assertEquals("t3.large", row.suggestedInstanceType());
    }

    @Test
    void aScaleDownWithNoSavingIsNotListed() {
        when(instanceTypeProjection.getInstanceType()).thenReturn("t3.nano"); // nothing smaller to suggest

        assertEquals(0, service.getRightsizingCandidates(PageRequest.of(0, 25)).getTotalElements());
        assertEquals(java.util.Optional.empty(), service.findCandidate(VM_ID));
    }

    @Test
    void findCandidateReturnsTheVmsCurrentRecommendation() {
        assertEquals("t3.large", service.findCandidate(VM_ID).orElseThrow().suggestedInstanceType());
    }

    // ---- After a resize (E08-T06) ----

    private VmMetricDaily quietDay(int daysAgo) {
        VmMetricDaily d = org.mockito.Mockito.mock(VmMetricDaily.class);
        org.mockito.Mockito.lenient().when(d.getVm()).thenReturn(buildAwsVm());
        org.mockito.Mockito.lenient().when(d.getAvgCpuUtilization()).thenReturn(BigDecimal.valueOf(2));
        org.mockito.Mockito.lenient().when(d.getBucketDate()).thenReturn(java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(daysAgo)));
        return d;
    }

    private void resizedDaysAgo(int days) {
        when(instanceTypeProjection.getInstanceTypeChangedAt())
                .thenReturn(Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(days))));
    }

    @Test
    void dailyRowsFromBeforeTheResizeAreIgnored() {
        resizedDaysAgo(2);
        // Three quiet days, but only today and yesterday measure the new size.
        List<VmMetricDaily> days = List.of(quietDay(0), quietDay(1), quietDay(2));
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(days);

        assertEquals(0, service.getRightsizingCandidates(PageRequest.of(0, 25)).getTotalElements());
    }

    @Test
    void enoughPostResizeDaysMakeItACandidateAgainButComputeOptimizerWaitsForTheCooldown() {
        resizedDaysAgo(10);
        List<VmMetricDaily> days = List.of(quietDay(0), quietDay(1), quietDay(2));
        when(vmMetricDailyRepository.findByVmVmIdInAndBucketDateGreaterThanEqualOrderByVmVmIdAscBucketDateDesc(anyList(), any()))
                .thenReturn(days);
        org.mockito.Mockito.lenient().when(computeOptimizerService.getAllRecommendations(com.tcgdigital.vmcontrol.service.CloudTarget.of(REGION))).thenReturn(Map.of(INSTANCE_ID,
                new ComputeOptimizerService.Recommendation(INSTANCE_ID, "OVER_PROVISIONED", List.of("m6a.large"))));

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("cpu-threshold-rule", row.source()); // advice from the old size is ignored
        assertEquals("t3.large", row.suggestedInstanceType());
    }
}
