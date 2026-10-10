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
    @Mock private VmMetricDailyRepository.CpuStats cpuStats;
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
        when(vmMetricDailyRepository.findCpuStatsSince(anyList(), any())).thenReturn(List.of(cpuStats));

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

        when(instanceTypeProjection.getVmId()).thenReturn(VM_ID);
        when(instanceTypeProjection.getInstanceType()).thenReturn("t3.xlarge");
        when(vmInventorySnapshotRepository.findInstanceTypesByVmIds(anyList())).thenReturn(List.of(instanceTypeProjection));

        CostDataProvider.VmCostEstimate estimate = new CostDataProvider.VmCostEstimate(
                true, BigDecimal.valueOf(0.1664), BigDecimal.valueOf(720), 0, BigDecimal.valueOf(119.81));
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of(VM_ID, estimate));
    }

    @Test
    void fallsBackToCpuThresholdRuleWhenComputeOptimizerHasNoOpinion() {
        when(computeOptimizerService.getEc2Recommendations(REGION)).thenReturn(Map.of());

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("cpu-threshold-rule", row.source());
        assertEquals("t3.large", row.suggestedInstanceType());
        assertEquals(null, row.findingLevel());
    }

    @Test
    void prefersComputeOptimizerRecommendationWhenOneExists() {
        ComputeOptimizerService.Recommendation recommendation =
                new ComputeOptimizerService.Recommendation(INSTANCE_ID, "m6a.large", "OVER_PROVISIONED");
        when(computeOptimizerService.getEc2Recommendations(REGION)).thenReturn(Map.of(INSTANCE_ID, recommendation));

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

        when(computeOptimizerService.getEc2Recommendations(REGION)).thenReturn(Map.of());

        RightsizingCandidateDTO row = onlyCandidate();

        assertEquals("SCALE_UP", row.direction());
        assertEquals("cpu-threshold-rule", row.source());
        assertEquals("t3.2xlarge", row.suggestedInstanceType());
    }

    @Test
    void callsComputeOptimizerOncePerDistinctRegionNotPerVm() {
        when(computeOptimizerService.getEc2Recommendations(REGION)).thenReturn(Map.of());

        onlyCandidate();

        org.mockito.Mockito.verify(computeOptimizerService, org.mockito.Mockito.times(1)).getEc2Recommendations(REGION);
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
}
