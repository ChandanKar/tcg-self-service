package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import com.tcgdigital.vmcontrol.repository.VmMetricDailyRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rollup never re-aggregates the partly archived boundary day and never replaces a fuller
 * daily row with a thinner one (E12-T01, M17).
 */
@ExtendWith(MockitoExtension.class)
class VmMetricRollupServiceTest {

    @Mock private VmMetricSampleRepository sampleRepository;
    @Mock private VmMetricDailyRepository dailyRepository;
    @Mock private VmRepository vmRepository;

    private VmMetricRollupService service;
    private Vm vm;

    @BeforeEach
    void setUp() {
        service = new VmMetricRollupService(sampleRepository, dailyRepository, vmRepository);
        ReflectionTestUtils.setField(service, "lookbackDays", 30);
        vm = new Vm();
        vm.setVmId("vm-1");
        when(vmRepository.findByIsActiveTrue()).thenReturn(List.of(vm));
        lenient().when(sampleRepository.aggregateByVmForPeriod(any(), any())).thenReturn(List.of());
        lenient().when(dailyRepository.findByBucketDate(any())).thenReturn(List.of());
    }

    @Test
    void theBoundaryDayIsNotReaggregated() {
        service.rollupDailyMetrics();

        Timestamp boundaryStart = Timestamp.valueOf(LocalDate.now().minusDays(30).atStartOfDay());
        verify(sampleRepository, never()).aggregateByVmForPeriod(org.mockito.ArgumentMatchers.eq(boundaryStart), any());
        verify(sampleRepository).aggregateByVmForPeriod(
                org.mockito.ArgumentMatchers.eq(Timestamp.valueOf(LocalDate.now().minusDays(29).atStartOfDay())), any());
    }

    @Test
    void aFullerExistingRowIsKept() {
        Date day = Date.valueOf(LocalDate.now().minusDays(29));
        VmMetricDaily full = new VmMetricDaily();
        full.setVm(vm);
        full.setBucketDate(day);
        full.setSampleCount(288);
        full.setAvgCpuUtilization(BigDecimal.valueOf(40));
        when(dailyRepository.findByBucketDate(day)).thenReturn(List.of(full));
        VmMetricSampleRepository.DailyAggregate partial = mock(VmMetricSampleRepository.DailyAggregate.class);
        when(partial.getVmId()).thenReturn("vm-1");
        lenient().when(partial.getSampleCount()).thenReturn(100L);
        when(sampleRepository.aggregateByVmForPeriod(org.mockito.ArgumentMatchers.eq(
                Timestamp.valueOf(day.toLocalDate().atStartOfDay())), any())).thenReturn(List.of(partial));

        service.rollupDailyMetrics();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VmMetricDaily>> saved = ArgumentCaptor.forClass(List.class);
        verify(dailyRepository, atLeastOnce()).saveAll(saved.capture());
        List<VmMetricDaily> all = new ArrayList<>();
        saved.getAllValues().forEach(all::addAll);
        assertThat(all).noneMatch(d -> d == full);
        assertThat(full.getSampleCount()).isEqualTo(288);
        assertThat(full.getAvgCpuUtilization()).isEqualByComparingTo("40");
    }

    private List<VmMetricDaily> rollUpToday(VmMetricSampleRepository.DailyAggregate agg) {
        when(sampleRepository.aggregateByVmForPeriod(org.mockito.ArgumentMatchers.eq(
                Timestamp.valueOf(LocalDate.now().atStartOfDay())), any())).thenReturn(List.of(agg));
        service.rollupDailyMetrics();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VmMetricDaily>> saved = ArgumentCaptor.forClass(List.class);
        verify(dailyRepository, atLeastOnce()).saveAll(saved.capture());
        List<VmMetricDaily> all = new ArrayList<>();
        saved.getAllValues().forEach(all::addAll);
        return all;
    }

    private VmMetricSampleRepository.DailyAggregate aggregate(BigDecimal avgMemory, BigDecimal maxMemory, long memorySamples) {
        VmMetricSampleRepository.DailyAggregate agg = mock(VmMetricSampleRepository.DailyAggregate.class);
        when(agg.getVmId()).thenReturn("vm-1");
        lenient().when(agg.getSampleCount()).thenReturn(288L);
        lenient().when(agg.getAvgCpu()).thenReturn(BigDecimal.valueOf(12));
        lenient().when(agg.getAvgMemory()).thenReturn(avgMemory);
        lenient().when(agg.getMaxMemory()).thenReturn(maxMemory);
        lenient().when(agg.getMemorySampleCount()).thenReturn(memorySamples);
        return agg;
    }

    @Test
    void memoryFromTheAgentIsRolledUp() {
        List<VmMetricDaily> all = rollUpToday(aggregate(new BigDecimal("41.500"), new BigDecimal("77.250"), 288));

        VmMetricDaily today = all.stream().filter(d -> d.getBucketDate().equals(Date.valueOf(LocalDate.now())))
                .findFirst().orElseThrow();
        assertThat(today.getAvgMemoryUtilization()).isEqualByComparingTo("41.5");
        assertThat(today.getMaxMemoryUtilization()).isEqualByComparingTo("77.25");
        assertThat(today.getMemorySampleCount()).isEqualTo(288);
    }

    @Test
    void withoutTheAgentMemoryIsNullAndCountZero() {
        List<VmMetricDaily> all = rollUpToday(aggregate(null, null, 0));

        VmMetricDaily today = all.stream().filter(d -> d.getBucketDate().equals(Date.valueOf(LocalDate.now())))
                .findFirst().orElseThrow();
        assertThat(today.getSampleCount()).isEqualTo(288);
        assertThat(today.getAvgCpuUtilization()).isEqualByComparingTo("12");
        assertThat(today.getAvgMemoryUtilization()).isNull();
        assertThat(today.getMaxMemoryUtilization()).isNull();
        assertThat(today.getMemorySampleCount()).isZero();
        // gap-filled days carry no memory either
        assertThat(all).filteredOn(d -> d.getSampleCount() == 0)
                .allSatisfy(d -> assertThat(d.getMemorySampleCount()).isZero());
    }
}
