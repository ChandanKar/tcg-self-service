package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EnvironmentInsightsDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Environment insights load per-VM data in a fixed number of batch queries and keep groups that
 * share a display name apart (E10-T04, M36).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentInsightsServiceTest {

    @Mock private EnvironmentService environmentService;
    @Mock private VmGroupRepository groupRepository;
    @Mock private VmRepository vmRepository;
    @Mock private VmInventorySnapshotRepository inventoryRepository;
    @Mock private VmVolumeSnapshotRepository volumeRepository;
    @Mock private VmMetricSampleRepository sampleRepository;
    @Mock private VmIdleSummaryRepository idleSummaryRepository;

    private EnvironmentInsightsService service;
    private VmGroup web1;
    private VmGroup web2;

    private static VmGroup group(String id, Environment env, int seq) {
        VmGroup g = new VmGroup();
        g.setGroupId(id);
        g.setName(id);
        g.setDisplayName("Web"); // both groups show as "Web"
        g.setSequencePosition(seq);
        g.setEnvironment(env);
        return g;
    }

    private static Vm vm(String id, VmGroup group, VmStatus status) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setName(id);
        vm.setGroup(group);
        vm.setStatus(status);
        vm.setRegion("ap-south-1");
        vm.setProvider(CloudProvider.AWS);
        return vm;
    }

    private static VmMetricSample sample(Vm vm, String cpu, Instant at) {
        VmMetricSample s = new VmMetricSample();
        s.setVm(vm);
        s.setSampleTime(Timestamp.from(at));
        s.setCpuUtilization(new BigDecimal(cpu));
        return s;
    }

    @BeforeEach
    void setUp() {
        service = new EnvironmentInsightsService(environmentService, groupRepository, vmRepository,
                inventoryRepository, volumeRepository, sampleRepository, idleSummaryRepository);
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("prod");
        when(environmentService.getEnvironmentById("env-1")).thenReturn(env);
        web1 = group("g-web-1", env, 1);
        web2 = group("g-web-2", env, 2);
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc("env-1")).thenReturn(List.of(web1, web2));
    }

    @Test
    void perVmDataIsLoadedInBatchesNotPerVm() {
        Vm a = vm("vm-a", web1, VmStatus.RUNNING);
        Vm b = vm("vm-b", web1, VmStatus.STOPPED);
        Vm c = vm("vm-c", web2, VmStatus.RUNNING);
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of(a, b, c));
        Instant now = Instant.now();
        when(sampleRepository.findLatestByVmIds(anyList())).thenReturn(List.of(
                sample(a, "40", now.minusSeconds(60)), sample(c, "80", now.minusSeconds(30))));

        EnvironmentInsightsDTO insights = service.getInsights("env-1");

        List<String> ids = List.of("vm-a", "vm-b", "vm-c");
        verify(inventoryRepository, times(1)).findByVmVmIdIn(ids);
        verify(volumeRepository, times(1)).findByVmVmIdIn(ids);
        verify(idleSummaryRepository, times(1)).findByVmVmIdIn(ids);
        verify(sampleRepository, times(1)).findLatestByVmIds(ids);
        verify(sampleRepository, times(1)).findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(any(), any(), any());
        verify(inventoryRepository, never()).findByVmVmId(anyString());
        verify(volumeRepository, never()).findByVmVmIdOrderByDeviceNameAsc(anyString());
        verify(idleSummaryRepository, never()).findByVmVmId(anyString());
        verify(sampleRepository, never()).findTopByVmVmIdOrderBySampleTimeDesc(anyString());
        verify(sampleRepository, never()).findByVmVmIdAndSampleTimeBetweenOrderBySampleTimeAsc(anyString(), any(), any());
        assertThat(insights.totalVms()).isEqualTo(3);
        assertThat(insights.avgCpuUtilization()).isEqualByComparingTo("60");
        assertThat(insights.missingMetricVms()).isEqualTo(1);
    }

    @Test
    void groupsThatShareADisplayNameAreKeptApart() {
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of(
                vm("vm-a", web1, VmStatus.RUNNING), vm("vm-b", web1, VmStatus.RUNNING), vm("vm-c", web2, VmStatus.STOPPED)));

        EnvironmentInsightsDTO insights = service.getInsights("env-1");

        assertThat(insights.groups()).extracting(EnvironmentInsightsDTO.GroupInsightDTO::groupId,
                        EnvironmentInsightsDTO.GroupInsightDTO::totalVms, EnvironmentInsightsDTO.GroupInsightDTO::runningVms)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("g-web-1", 2, 2),
                        org.assertj.core.groups.Tuple.tuple("g-web-2", 1, 0));
        assertThat(insights.busiestVms()).isEmpty();
    }

    @Test
    void anEmptyEnvironmentIssuesNoInQueries() {
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of());

        EnvironmentInsightsDTO insights = service.getInsights("env-1");

        assertThat(insights.totalVms()).isZero();
        verify(inventoryRepository, never()).findByVmVmIdIn(anyList());
        verify(sampleRepository, never()).findLatestByVmIds(anyList());
        verify(sampleRepository, never()).findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(anyList(), any(), any());
    }

    // ---------------------------------------------------------------- U08-T08: window and actions

    private static VmMetricSampleRepository.VmCpuAverage avg(String vmId, double cpu) {
        return new VmMetricSampleRepository.VmCpuAverage() {
            public String getVmId() { return vmId; }
            public Double getAvgCpu() { return cpu; }
        };
    }

    @Test
    void sevenDaysRanksTheBusiestByTheirSevenDayAverage() {
        Vm a = vm("vm-a", web1, VmStatus.RUNNING);
        Vm c = vm("vm-c", web2, VmStatus.RUNNING);
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of(a, c));
        Instant now = Instant.now();
        when(sampleRepository.findLatestByVmIds(anyList())).thenReturn(List.of(
                sample(a, "40", now.minusSeconds(60)), sample(c, "80", now.minusSeconds(30))));
        when(sampleRepository.avgCpuByVmIdsSince(anyList(), any())).thenReturn(List.of(avg("vm-a", 22.5), avg("vm-c", 5)));

        EnvironmentInsightsDTO insights = service.getInsights("env-1", null, "7d");

        org.mockito.ArgumentCaptor<Timestamp> since = org.mockito.ArgumentCaptor.forClass(Timestamp.class);
        verify(sampleRepository).avgCpuByVmIdsSince(anyList(), since.capture());
        assertThat(java.time.Duration.between(since.getValue().toInstant(), now).toHours()).isBetween(167L, 169L);
        assertThat(insights.window()).isEqualTo("7d");
        assertThat(insights.busiestVms()).extracting(EnvironmentInsightsDTO.VmInsightRowDTO::vmId).containsExactly("vm-a", "vm-c");
        assertThat(insights.busiestVms().get(0).avgCpuWindow()).isEqualByComparingTo("22.50");
    }

    @Test
    void onlyTwentyFourHoursAndSevenDaysAreWindows() {
        assertThat(EnvironmentInsightsService.parseWindow(null)).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(EnvironmentInsightsService.parseWindow("7d")).isEqualTo(java.time.Duration.ofDays(7));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EnvironmentInsightsService.parseWindow("30d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionsStopTheIdleVmResizeTheOversizedOneAndStopOnWeekends() {
        CostDataProvider costs = org.mockito.Mockito.mock(CostDataProvider.class);
        CostEstimationService estimation = org.mockito.Mockito.mock(CostEstimationService.class);
        AutomationRuleService rules = org.mockito.Mockito.mock(AutomationRuleService.class);
        service.setCollaborators(costs, estimation, rules);

        Vm cache = vm("pay-cache", web1, VmStatus.RUNNING);
        Vm api = vm("pay-api", web2, VmStatus.RUNNING);
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of(cache, api));
        com.tcgdigital.vmcontrol.model.VmIdleSummary idle = new com.tcgdigital.vmcontrol.model.VmIdleSummary();
        idle.setVm(cache);
        idle.setIdle(true);
        idle.setIdleDurationMinutes(190);
        when(idleSummaryRepository.findByVmVmIdIn(anyList())).thenReturn(List.of(idle));
        when(costs.estimateCosts(anyList(), any(), any())).thenReturn(java.util.Map.of("pay-cache",
                new CostDataProvider.VmCostEstimate(true, new BigDecimal("0.0700"), BigDecimal.ONE, 0, new BigDecimal("0.07"), false, null)));
        when(estimation.findCandidates(any())).thenReturn(java.util.Map.of("pay-api",
                new com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO("pay-api", "pay-api", "env-1", "prod", "m6i.large", "m6i.medium",
                        new BigDecimal("9"), new BigDecimal("18"), new BigDecimal("70"), new BigDecimal("39"), new BigDecimal("31"),
                        true, "cpu-threshold-rule", "LOW", "SCALE_DOWN", "RUNNING", false)));
        when(rules.nextScheduledFirings(any(), any())).thenReturn(new AutomationRuleService.NextFirings(1, null, null));
        when(sampleRepository.weekendIdleSecondsByVmIds(anyList(), any(), any())).thenReturn(List.of(
                idleSeconds("pay-cache", 48 * 3600), idleSeconds("pay-api", 3600)));

        List<EnvironmentInsightsDTO.ActionDTO> actions = service.getInsights("env-1", null, "24h").actions();

        assertThat(actions).extracting(EnvironmentInsightsDTO.ActionDTO::kind)
                .containsExactly("STOP_IDLE", "RESIZE", "SCHEDULE_WEEKENDS");
        assertThat(actions.get(0).vmName()).isEqualTo("pay-cache");
        assertThat(actions.get(0).hourlyRate()).isEqualByComparingTo("0.07");
        assertThat(actions.get(0).detail()).isEqualTo("Idle for 3 h");
        assertThat(actions.get(1).recommendedType()).isEqualTo("m6i.medium");
        assertThat(actions.get(1).monthlySavings()).isEqualByComparingTo("31");
        assertThat(actions.get(2).detail()).isEqualTo("2 VMs ran idle for about 49 h over the weekend");
    }

    @Test
    void noScheduleMeansNoWeekendAdviceAndNoCollaboratorsMeansNoActions() {
        Vm a = vm("vm-a", web1, VmStatus.RUNNING);
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(List.of(a));
        assertThat(service.getInsights("env-1", null, "24h").actions()).isEmpty();

        AutomationRuleService rules = org.mockito.Mockito.mock(AutomationRuleService.class);
        service.setCollaborators(null, null, rules);
        when(rules.nextScheduledFirings(any(), any())).thenReturn(new AutomationRuleService.NextFirings(0, null, null));
        assertThat(service.getInsights("env-1", null, "24h").actions()).isEmpty();
        verify(sampleRepository, never()).weekendIdleSecondsByVmIds(anyList(), any(), any());
    }

    private static VmMetricSampleRepository.VmIdleSeconds idleSeconds(String vmId, long seconds) {
        return new VmMetricSampleRepository.VmIdleSeconds() {
            public String getVmId() { return vmId; }
            public Number getSeconds() { return seconds; }
        };
    }
}
