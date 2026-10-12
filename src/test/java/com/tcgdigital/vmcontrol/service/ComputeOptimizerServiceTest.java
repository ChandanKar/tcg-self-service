package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerOption;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerRecommendation;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.ComputeOptimizerRecommendationRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.ComputeOptimizerException;
import software.amazon.awssdk.services.computeoptimizer.model.EstimatedMonthlySavings;
import software.amazon.awssdk.services.computeoptimizer.model.Finding;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsResponse;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendationOption;
import software.amazon.awssdk.services.computeoptimizer.model.MetricName;
import software.amazon.awssdk.services.computeoptimizer.model.PlatformDifference;
import software.amazon.awssdk.services.computeoptimizer.model.SavingsOpportunity;
import software.amazon.awssdk.services.computeoptimizer.model.UtilizationMetric;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Parsing (E08-T05), the daily capture of every page and option (E30-T04), and reads served from
 * the stored rows without an AWS call.
 */
class ComputeOptimizerServiceTest {

    private static final String REGION = "us-east-1";

    private final ComputeOptimizerClient client = mock(ComputeOptimizerClient.class);
    private final ComputeOptimizerRecommendationRepository repository = mock(ComputeOptimizerRecommendationRepository.class);
    private final VmRepository vmRepository = mock(VmRepository.class);
    private final java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-10-12T05:45:00Z"), java.time.ZoneOffset.UTC);
    private final ComputeOptimizerService service = enabled();

    private ComputeOptimizerService enabled() {
        ComputeOptimizerService s = new ComputeOptimizerService(region -> client, clock, repository, vmRepository);
        ReflectionTestUtils.setField(s, "enabled", true);
        return s;
    }

    private static InstanceRecommendation rec(String id, Finding finding, InstanceRecommendationOption... options) {
        return InstanceRecommendation.builder()
                .instanceArn("arn:aws:ec2:us-east-1:123456789012:instance/" + id)
                .currentInstanceType("m5.xlarge")
                .finding(finding)
                .recommendationOptions(options)
                .build();
    }

    private static InstanceRecommendationOption option(String type, int rank) {
        return InstanceRecommendationOption.builder().instanceType(type).rank(rank).build();
    }

    private static Vm vm(String vmId, String instanceId) {
        Vm vm = new Vm();
        vm.setVmId(vmId);
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId(instanceId);
        vm.setRegion(REGION);
        return vm;
    }

    // ---- Parsing (E08-T05, M11) ----

    @Test
    void parseRecommendations_extractsInstanceIdFromArnAndTopRankedOption() {
        GetEc2InstanceRecommendationsResponse response = GetEc2InstanceRecommendationsResponse.builder()
                .instanceRecommendations(rec("i-0abc123def456", Finding.OVERPROVISIONED, option("m6a.xlarge", 2), option("m6a.large", 1)))
                .build();

        Map<String, ComputeOptimizerService.Recommendation> result = service.parseRecommendations(response);

        assertEquals(1, result.size());
        ComputeOptimizerService.Recommendation r = result.get("i-0abc123def456");
        assertEquals("m6a.large", r.suggestedInstanceType(), "must pick rank=1, not the first list entry");
        assertEquals("OVER_PROVISIONED", r.finding());
    }

    @Test
    void parseRecommendations_skipsRecommendationWithNoOptions() {
        GetEc2InstanceRecommendationsResponse response = GetEc2InstanceRecommendationsResponse.builder()
                .instanceRecommendations(rec("i-optimized", Finding.OPTIMIZED))
                .build();

        assertTrue(service.parseRecommendations(response).isEmpty());
    }

    @Test
    void onlyOverprovisionedInstancesAreRecommendationsWithEveryOptionInRankOrder() {
        GetEc2InstanceRecommendationsResponse response = GetEc2InstanceRecommendationsResponse.builder()
                .instanceRecommendations(
                        rec("i-over", Finding.OVERPROVISIONED, option("m6a.xlarge", 2), option("m6a.large", 1), option("t3.large", 3)),
                        rec("i-under", Finding.UNDERPROVISIONED, option("m6a.2xlarge", 1)),
                        rec("i-ok", Finding.OPTIMIZED, option("m6a.large", 1)))
                .build();

        Map<String, ComputeOptimizerService.Recommendation> result = service.parseRecommendations(response);

        assertEquals(java.util.Set.of("i-over"), result.keySet());
        assertEquals(List.of("m6a.large", "m6a.xlarge", "t3.large"), result.get("i-over").optionTypesByRank());
    }

    // ---- Daily capture (E30-T04) ----

    @Test
    void everyPageIsStoredWithEveryOptionRiskEffortAndArchitectureDifference() {
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm("vm-1", "i-1")));
        when(repository.findByRegionAndAwsAccountRef(REGION, AwsAccount.DEFAULT_REF)).thenReturn(List.of());
        InstanceRecommendation first = InstanceRecommendation.builder()
                .instanceArn("arn:aws:ec2:us-east-1:1:instance/i-1")
                .currentInstanceType("m5.xlarge")
                .finding(Finding.OVERPROVISIONED)
                .findingReasonCodesWithStrings("CPUOverprovisioned", "MemoryOverprovisioned")
                .lookBackPeriodInDays(14.0)
                .utilizationMetrics(UtilizationMetric.builder().name(MetricName.MEMORY).value(31.0).build())
                .recommendationOptions(
                        InstanceRecommendationOption.builder().instanceType("m7g.large").rank(2).performanceRisk(1.0)
                                .migrationEffort("Medium").platformDifferences(PlatformDifference.ARCHITECTURE)
                                .savingsOpportunity(SavingsOpportunity.builder().estimatedMonthlySavings(
                                        EstimatedMonthlySavings.builder().value(80.4567).build()).build())
                                .build(),
                        InstanceRecommendationOption.builder().instanceType("m5.large").rank(1).performanceRisk(0.5)
                                .migrationEffort("VeryLow").build())
                .build();
        when(client.getEC2InstanceRecommendations(any(GetEc2InstanceRecommendationsRequest.class)))
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder().instanceRecommendations(first).nextToken("page-2").build())
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder()
                        .instanceRecommendations(rec("i-unmanaged", Finding.UNDERPROVISIONED, option("m5.2xlarge", 1))).build());

        assertEquals(2, service.captureRecommendations());

        ArgumentCaptor<GetEc2InstanceRecommendationsRequest> requests = ArgumentCaptor.forClass(GetEc2InstanceRecommendationsRequest.class);
        verify(client, times(2)).getEC2InstanceRecommendations(requests.capture());
        assertEquals("page-2", requests.getAllValues().get(1).nextToken());

        ArgumentCaptor<ComputeOptimizerRecommendation> saved = ArgumentCaptor.forClass(ComputeOptimizerRecommendation.class);
        verify(repository, times(2)).save(saved.capture());
        ComputeOptimizerRecommendation managed = saved.getAllValues().get(0);
        assertEquals("i-1", managed.getProviderVmId());
        assertEquals("vm-1", managed.getVmId());
        assertEquals(AwsAccount.DEFAULT_REF, managed.getAwsAccountRef());
        assertEquals("OVER_PROVISIONED", managed.getFinding());
        assertEquals("CPUOverprovisioned,MemoryOverprovisioned", managed.getFindingReasons());
        assertEquals(14, managed.getLookbackDays());
        assertTrue(managed.isMemoryMetricPresent());
        assertEquals(Timestamp.from(clock.instant()), managed.getCapturedAt());
        List<ComputeOptimizerOption> options = managed.getOptions();
        assertEquals(List.of("m5.large", "m7g.large"), options.stream().map(ComputeOptimizerOption::getInstanceType).toList());
        ComputeOptimizerOption graviton = options.get(1);
        assertEquals("Architecture", graviton.getPlatformDifferences());
        assertEquals("Medium", graviton.getMigrationEffort());
        assertEquals(0, new BigDecimal("1.00").compareTo(graviton.getPerformanceRisk()));
        assertEquals(0, new BigDecimal("80.46").compareTo(graviton.getEstimatedMonthlySavings()));
        assertNull(options.get(0).getPlatformDifferences());

        ComputeOptimizerRecommendation unmanaged = saved.getAllValues().get(1);
        assertEquals("UNDER_PROVISIONED", unmanaged.getFinding());
        assertNull(unmanaged.getVmId());
        verify(repository).deleteByCapturedAtBefore(Timestamp.from(clock.instant().minus(java.time.Duration.ofDays(7))));
    }

    @Test
    void anExistingRowIsReplacedAndAnInstanceNoLongerReportedIsRemoved() {
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm("vm-1", "i-1")));
        ComputeOptimizerRecommendation existing = new ComputeOptimizerRecommendation();
        existing.setProviderVmId("i-1");
        existing.setRegion(REGION);
        existing.setAwsAccountRef(AwsAccount.DEFAULT_REF);
        ComputeOptimizerOption old = new ComputeOptimizerOption();
        old.setInstanceType("t3.nano");
        old.setOptionRank(1);
        existing.addOption(old);
        ComputeOptimizerRecommendation gone = new ComputeOptimizerRecommendation();
        gone.setProviderVmId("i-gone");
        gone.setRegion(REGION);
        gone.setAwsAccountRef(AwsAccount.DEFAULT_REF);
        when(repository.findByRegionAndAwsAccountRef(REGION, AwsAccount.DEFAULT_REF)).thenReturn(List.of(existing, gone));
        when(client.getEC2InstanceRecommendations(any(GetEc2InstanceRecommendationsRequest.class)))
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder()
                        .instanceRecommendations(rec("i-1", Finding.OVERPROVISIONED, option("m5.large", 1))).build());

        service.captureRecommendations();

        verify(repository).save(existing);
        assertEquals(List.of("m5.large"), existing.getOptions().stream().map(ComputeOptimizerOption::getInstanceType).toList());
        verify(repository).delete(gone);
        verify(repository, never()).delete(existing);
    }

    @Test
    void aFailingTargetStoresNothingAndKeepsItsRows() {
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm("vm-1", "i-1")));
        when(client.getEC2InstanceRecommendations(any(GetEc2InstanceRecommendationsRequest.class)))
                .thenThrow(ComputeOptimizerException.builder().message("throttled").build());

        assertEquals(0, service.captureRecommendations());
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any(ComputeOptimizerRecommendation.class));
    }

    @Test
    void turnedOffNothingIsCalledOrRead() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertEquals(0, service.captureRecommendations());
        assertTrue(service.getEc2Recommendations(CloudTarget.of(REGION)).isEmpty());
        verify(client, never()).getEC2InstanceRecommendations(any(GetEc2InstanceRecommendationsRequest.class));
        verify(vmRepository, never()).findByIsActiveTrueFetchGroupAndEnvironment();
        verify(repository, never()).findByRegionAndAwsAccountRef(anyString(), anyString());
    }

    // ---- Reads come from the stored rows (E30-T04) ----

    private static ComputeOptimizerRecommendation stored(String id, String finding, String... types) {
        ComputeOptimizerRecommendation r = new ComputeOptimizerRecommendation();
        r.setProviderVmId(id);
        r.setRegion(REGION);
        r.setAwsAccountRef(AwsAccount.DEFAULT_REF);
        r.setFinding(finding);
        for (int i = 0; i < types.length; i++) {
            ComputeOptimizerOption o = new ComputeOptimizerOption();
            o.setInstanceType(types[i]);
            o.setOptionRank(i + 1);
            o.setPerformanceRisk(new BigDecimal("1.50"));
            o.setPlatformDifferences(types[i].contains("g.") ? "Architecture" : null);
            r.addOption(o);
        }
        return r;
    }

    @Test
    void theScaleDownOverlayReadsStoredOverProvisionedRowsWithoutCallingAws() {
        when(repository.findByRegionAndAwsAccountRef(REGION, AwsAccount.DEFAULT_REF)).thenReturn(List.of(
                stored("i-over", "OVER_PROVISIONED", "m5.large", "m7g.large"),
                stored("i-under", "UNDER_PROVISIONED", "m5.2xlarge"),
                stored("i-empty", "OVER_PROVISIONED")));

        Map<String, ComputeOptimizerService.Recommendation> overlay = service.getEc2Recommendations(CloudTarget.of(REGION));

        assertEquals(java.util.Set.of("i-over"), overlay.keySet());
        ComputeOptimizerService.Recommendation r = overlay.get("i-over");
        assertEquals(List.of("m5.large", "m7g.large"), r.optionTypesByRank());
        assertFalse(r.options().get(0).changesArchitecture());
        assertTrue(r.options().get(1).changesArchitecture());
        assertEquals(0, new BigDecimal("1.5").compareTo(r.options().get(1).performanceRisk()));
        assertEquals(3, service.getAllRecommendations(CloudTarget.of(REGION)).size());
        verify(client, never()).getEC2InstanceRecommendations(any(GetEc2InstanceRecommendationsRequest.class));
    }
}
