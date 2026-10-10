package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostSetupStatusDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.PolicyEvaluationDecisionType;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cost Setup (E08-T11): the status makes no AWS call; the pre-flight maps IAM simulation to
 * ALLOWED/DENIED, falls back to UNKNOWN when simulation itself is denied, still runs its probes,
 * and is reused for 60 seconds.
 */
@ExtendWith(MockitoExtension.class)
class CostSetupServiceTest {

    @Mock private ScheduledJobLockRepository jobLocks;
    @Mock private VmRepository vmRepository;
    @Mock private ComputeOptimizerService computeOptimizer;
    @Mock private AwsCloudProviderService aws;
    @Mock private CostExplorerTagActivationService tagActivation;
    @Mock private StsClient sts;
    @Mock private IamClient iam;

    private Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private CostSetupService service;

    @BeforeEach
    void setUp() {
        service = new CostSetupService(jobLocks, vmRepository, new CostExplorerClientProvider("k", "s"), computeOptimizer,
                aws, tagActivation, sts, iam,
                Map.of("snapshot", true, "actuals", true, "tagging", false, "optimizer", true,
                        "reservations", true, "weeklyReports", true),
                "us-east-1", clock);
        ScheduledJobLock lock = new ScheduledJobLock();
        lock.setAcquiredAt(Timestamp.from(Instant.parse("2026-10-07T05:00:00Z")));
        lenient().when(jobLocks.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(jobLocks.findById("actual_cost_ingestion")).thenReturn(Optional.of(lock));
        Vm vm = new Vm();
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId("i-1");
        vm.setRegion("ap-south-1");
        lenient().when(vmRepository.findByIsActiveTrue()).thenReturn(List.of(vm));
    }

    private CostSetupStatusDTO.Feature feature(CostSetupStatusDTO dto, String key) {
        return dto.features().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    private void callerIs(String arn) {
        when(sts.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder().arn(arn).build());
    }

    private void probesAnswer() {
        when(computeOptimizer.getEnrollmentStatus("ap-south-1")).thenReturn("Inactive");
        when(aws.canTagInstance("ap-south-1", "i-1")).thenReturn(true);
        when(tagActivation.tagKeyStatus(any())).thenReturn(Map.of("tcg:environment", "Active"));
    }

    @Test
    void theStatusShowsSwitchesAndLastRunsWithoutAnyAwsCall() {
        CostSetupStatusDTO status = service.getStatus();

        assertThat(feature(status, "tagging").enabled()).isFalse();
        assertThat(feature(status, "actuals").enabled()).isTrue();
        assertThat(feature(status, "actuals").property()).isEqualTo("cost.actuals.enabled");
        assertThat(feature(status, "actuals").lastRunAt()).isEqualTo(Timestamp.from(Instant.parse("2026-10-07T05:00:00Z")));
        assertThat(feature(status, "tagging").extra()).containsKeys("taggedVmCount", "untaggedVmCount");
        verifyNoInteractions(sts, iam, computeOptimizer, aws, tagActivation);
    }

    @Test
    void simulationResultsMapToAllowedAndDenied() {
        callerIs("arn:aws:iam::123456789012:user/self-service-user");
        when(iam.simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class))).thenAnswer(inv -> {
            SimulatePrincipalPolicyRequest req = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().isTruncated(false).evaluationResults(req.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a).evalDecision(a.startsWith("ce:")
                            ? PolicyEvaluationDecisionType.IMPLICIT_DENY : PolicyEvaluationDecisionType.ALLOWED).build())
                    .toList()).build();
        });
        probesAnswer();

        CostSetupStatusDTO result = service.runChecks();

        assertThat(result.principalArn()).isEqualTo("arn:aws:iam::123456789012:user/self-service-user");
        CostSetupStatusDTO.Check actuals = feature(result, "actuals").checks().get(0);
        assertThat(actuals.action()).isEqualTo("ce:GetCostAndUsage");
        assertThat(actuals.result()).isEqualTo("DENIED");
        assertThat(feature(result, "optimizer").checks()).allMatch(c -> c.result().equals("ALLOWED"));
        assertThat(result.probes()).extracting(CostSetupStatusDTO.Probe::result).contains("ALLOWED", "DENIED");
    }

    @Test
    void aDeniedSimulationLeavesUnknownWithAHintAndTheProbesStillReport() {
        callerIs("arn:aws:iam::123456789012:user/self-service-user");
        when(iam.simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class)))
                .thenThrow(IamException.builder().message("not authorized to perform: iam:SimulatePrincipalPolicy").build());
        probesAnswer();

        CostSetupStatusDTO result = service.runChecks();

        assertThat(feature(result, "actuals").checks().get(0).result()).isEqualTo("UNKNOWN");
        assertThat(feature(result, "actuals").checks().get(0).hint()).contains("iam:SimulatePrincipalPolicy");
        assertThat(result.probes()).hasSize(3);
        assertThat(result.probes().get(1).name()).contains("ap-south-1");
        assertThat(result.probes().get(1).result()).isEqualTo("ALLOWED");
    }

    @Test
    void aSecondCheckWithin60SecondsReusesTheFirst() {
        callerIs("arn:aws:iam::123456789012:user/u");
        when(iam.simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class)))
                .thenReturn(SimulatePrincipalPolicyResponse.builder().isTruncated(false).build());
        probesAnswer();

        service.runChecks();
        now = now.plusSeconds(30);
        service.runChecks();
        verify(sts, times(1)).getCallerIdentity();

        now = now.plusSeconds(31);
        service.runChecks();
        verify(sts, times(2)).getCallerIdentity();
    }

    @Test
    void anAssumedRoleSessionIsSimulatedAsItsRole() {
        assertThat(CostSetupService.policySourceArn("arn:aws:sts::123456789012:assumed-role/CostRole/session-1"))
                .isEqualTo("arn:aws:iam::123456789012:role/CostRole");
        assertThat(CostSetupService.policySourceArn("arn:aws:iam::123456789012:user/u"))
                .isEqualTo("arn:aws:iam::123456789012:user/u");
    }
}
