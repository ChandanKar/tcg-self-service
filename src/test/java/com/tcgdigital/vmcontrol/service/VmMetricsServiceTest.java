package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmIdleSummaryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A metrics sync fills the gap since each VM's latest stored sample, capped at
 * backfill-max-hours, skips samples already stored and updates the idle summary once per VM
 * (E12-T03).
 */
@ExtendWith(MockitoExtension.class)
class VmMetricsServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private VmMetricSampleRepository sampleRepository;
    @Mock private VmIdleSummaryRepository idleSummaryRepository;
    @Mock private VmInventorySnapshotRepository inventoryRepository;
    @Mock private CloudMetricsProviderFactory providerFactory;
    @Mock private CloudMetricsProviderService provider;
    @Mock private PlatformTransactionManager transactionManager;

    private VmMetricsService service;
    private Vm vm;

    @BeforeEach
    void setUp() {
        service = new VmMetricsService(vmRepository, sampleRepository, idleSummaryRepository, inventoryRepository,
                providerFactory, transactionManager);
        ReflectionTestUtils.setField(service, "periodSeconds", 300);
        ReflectionTestUtils.setField(service, "backfillMaxHours", 3);
        ReflectionTestUtils.setField(service, "idleMinimumDurationMinutes", 30);
        ReflectionTestUtils.setField(service, "idleCpuThresholdPercent", BigDecimal.valueOf(5));
        vm = new Vm();
        vm.setVmId("vm-1");
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId("i-1");
        vm.setRegion("us-east-1");
        vm.setStatus(VmStatus.RUNNING);
        lenient().when(vmRepository.findByStatus(VmStatus.RUNNING)).thenReturn(List.of(vm));
        lenient().when(providerFactory.getService(CloudProvider.AWS)).thenReturn(Optional.of(provider));
        lenient().when(provider.isAvailable()).thenReturn(true);
        lenient().when(sampleRepository.findByVmVmIdOrderBySampleTimeDesc(anyString(), any())).thenReturn(List.of());
    }

    private void latestStored(Instant at) {
        VmMetricSample latest = new VmMetricSample();
        latest.setVm(vm);
        latest.setSampleTime(Timestamp.from(at));
        when(sampleRepository.findLatestByVmIds(anyList())).thenReturn(List.of(latest));
    }

    private List<CloudMetricsProviderService.VmMetricData> points(Instant from, int count) {
        List<CloudMetricsProviderService.VmMetricData> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CloudMetricsProviderService.VmMetricData d = new CloudMetricsProviderService.VmMetricData();
            d.setProviderVmId("i-1");
            d.setPeriodSeconds(300);
            d.setSampleTime(Timestamp.from(from.plus(Duration.ofMinutes(5L * i))));
            d.setCpuUtilization(BigDecimal.TEN);
            list.add(d);
        }
        return list;
    }

    private Instant requestedStart() {
        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        verify(provider).fetchMetricSeries(anyList(), eq("us-east-1"), start.capture(), any(), anyInt());
        return start.getValue();
    }

    @Test
    void aMissedHalfHourIsBackfilledFromTheLatestStoredSample() {
        Instant latest = Instant.now().minus(Duration.ofMinutes(35));
        latestStored(latest);
        when(provider.fetchMetricSeries(anyList(), anyString(), any(), any(), anyInt()))
                .thenReturn(Map.of("i-1", points(latest.plus(Duration.ofMinutes(5)), 6)));
        VmMetricSample recent = new VmMetricSample();
        recent.setVm(vm);
        recent.setSampleTime(Timestamp.from(Instant.now()));
        recent.setCpuUtilization(BigDecimal.TEN);
        when(sampleRepository.findByVmVmIdOrderBySampleTimeDesc(anyString(), any())).thenReturn(List.of(recent));
        when(idleSummaryRepository.findByVmVmId("vm-1")).thenReturn(Optional.empty());

        assertThat(service.syncRunningVmMetrics()).isEqualTo(6);

        assertThat(requestedStart()).isCloseTo(latest.plusSeconds(1), within(Duration.ofSeconds(2)));
        verify(idleSummaryRepository, times(1)).save(any());
    }

    @Test
    void theBackfillIsCapped() {
        latestStored(Instant.now().minus(Duration.ofHours(10)));
        when(provider.fetchMetricSeries(anyList(), anyString(), any(), any(), anyInt())).thenReturn(Map.of());

        service.syncRunningVmMetrics();

        assertThat(requestedStart()).isCloseTo(Instant.now().minus(Duration.ofHours(3)), within(Duration.ofSeconds(5)));
    }

    @Test
    void alreadyStoredSamplesAreSkipped() {
        Instant latest = Instant.now().minus(Duration.ofMinutes(20));
        latestStored(latest);
        List<CloudMetricsProviderService.VmMetricData> pts = points(latest, 3); // the first is the latest stored one
        when(provider.fetchMetricSeries(anyList(), anyString(), any(), any(), anyInt())).thenReturn(Map.of("i-1", pts));
        when(sampleRepository.existsByVmVmIdAndSampleTimeAndPeriodSeconds(eq("vm-1"), any(), eq(300)))
                .thenAnswer(inv -> ((Timestamp) inv.getArgument(1)).toInstant().equals(latest));

        assertThat(service.syncRunningVmMetrics()).isEqualTo(2);
        verify(sampleRepository, times(2)).save(any(VmMetricSample.class));
    }

    // ---- Period bucketing and memory (E12-T04) ----

    private static VmMetricSample stored(Instant at, String cpu, String memory, long netIn) {
        VmMetricSample s = new VmMetricSample();
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(300);
        s.setCpuUtilization(new BigDecimal(cpu));
        s.setMemoryUtilization(memory == null ? null : new BigDecimal(memory));
        s.setNetworkInBytes(netIn);
        return s;
    }

    @Test
    void hourlyBucketsAverageUtilisationAndSumBytes() {
        Instant hour = Instant.parse("2026-10-11T10:00:00Z");
        List<VmMetricSample> samples = List.of(
                stored(hour, "10", "40", 100), stored(hour.plusSeconds(300), "20", "60", 200),
                stored(hour.plusSeconds(3600), "30", null, 50));

        List<com.tcgdigital.vmcontrol.dto.VmMetricsDTO.SampleDTO> points = VmMetricsService.bucket(samples, 3600);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).getSampleTime().toInstant()).isEqualTo(hour);
        assertThat(points.get(0).getCpuUtilization()).isEqualByComparingTo("15");
        assertThat(points.get(0).getMemoryUtilization()).isEqualByComparingTo("50");
        assertThat(points.get(0).getNetworkInBytes()).isEqualTo(300L);
        assertThat(points.get(1).getMemoryUtilization()).isNull();
    }

    @Test
    void anUnsupportedPeriodIsRejected() {
        com.tcgdigital.vmcontrol.model.Environment env = new com.tcgdigital.vmcontrol.model.Environment();
        env.setEnvironmentId("env-1");
        com.tcgdigital.vmcontrol.model.VmGroup group = new com.tcgdigital.vmcontrol.model.VmGroup();
        group.setEnvironment(env);
        vm.setGroup(group);
        when(vmRepository.findById("vm-1")).thenReturn(Optional.of(vm));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getMetrics("env-1", "vm-1", "1h", 7))
                .isInstanceOf(com.tcgdigital.vmcontrol.exception.ValidationException.class);
    }

    @Test
    void memoryIsInTheSeries() {
        com.tcgdigital.vmcontrol.model.Environment env = new com.tcgdigital.vmcontrol.model.Environment();
        env.setEnvironmentId("env-1");
        com.tcgdigital.vmcontrol.model.VmGroup group = new com.tcgdigital.vmcontrol.model.VmGroup();
        group.setEnvironment(env);
        vm.setGroup(group);
        when(vmRepository.findById("vm-1")).thenReturn(Optional.of(vm));
        when(sampleRepository.findByVmVmIdAndSampleTimeBetweenOrderBySampleTimeAsc(eq("vm-1"), any(), any()))
                .thenReturn(List.of(stored(Instant.now().minusSeconds(600), "10", "45", 1)));

        com.tcgdigital.vmcontrol.dto.VmMetricsDTO dto = service.getMetrics("env-1", "vm-1", "1h", 300);

        assertThat(dto.getSeries().get(0).getMemoryUtilization()).isEqualByComparingTo("45");
        assertThat(dto.getPeriodSeconds()).isEqualTo(300);
    }
}
