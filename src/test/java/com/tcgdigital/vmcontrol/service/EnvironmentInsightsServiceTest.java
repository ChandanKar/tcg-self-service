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
}
