package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationReportType;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationSnapshot;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.WeeklyOptimizationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklySnapshotServiceTest {

    @Mock private WeeklyOptimizationSnapshotRepository snapshotRepository;
    @Mock private EnvironmentRepository environmentRepository;

    private WeeklySnapshotService service;

    private final Date snapshotDate = Date.valueOf(LocalDate.of(2026, 7, 27));

    @BeforeEach
    void setUp() {
        service = new WeeklySnapshotService(snapshotRepository, environmentRepository);
    }

    @Test
    void getPreviousWeekTotals_returnsEmptyMapOnColdStart() {
        when(snapshotRepository.findByReportTypeAndSnapshotDate(WeeklyOptimizationReportType.IDLE_WASTE, snapshotDate))
                .thenReturn(java.util.List.of());

        Map<String, BigDecimal> totals = service.getPreviousWeekTotals(WeeklyOptimizationReportType.IDLE_WASTE, snapshotDate);

        assertTrue(totals.isEmpty());
    }

    @Test
    void getPreviousWeekTotals_returnsCapturedValuesKeyedByEnvironmentId() {
        Environment env = new Environment("env-A");
        WeeklyOptimizationSnapshot snapshot = new WeeklyOptimizationSnapshot();
        snapshot.setEnvironment(env);
        snapshot.setMetricValue(new BigDecimal("123.45"));

        when(snapshotRepository.findByReportTypeAndSnapshotDate(WeeklyOptimizationReportType.RIGHTSIZING, snapshotDate))
                .thenReturn(java.util.List.of(snapshot));

        Map<String, BigDecimal> totals = service.getPreviousWeekTotals(WeeklyOptimizationReportType.RIGHTSIZING, snapshotDate);

        assertEquals(new BigDecimal("123.45"), totals.get("env-A"));
    }

    @Test
    void captureSnapshot_createsNewRowWhenNoneExists() {
        Environment env = new Environment("env-A");
        when(snapshotRepository.findByEnvironment_EnvironmentIdAndReportTypeAndSnapshotDate(
                eq("env-A"), eq(WeeklyOptimizationReportType.IDLE_WASTE), eq(snapshotDate)))
                .thenReturn(Optional.empty());
        when(environmentRepository.findById("env-A")).thenReturn(Optional.of(env));

        service.captureSnapshot(WeeklyOptimizationReportType.IDLE_WASTE, snapshotDate, Map.of("env-A", new BigDecimal("50.00")));

        ArgumentCaptor<WeeklyOptimizationSnapshot> captor = ArgumentCaptor.forClass(WeeklyOptimizationSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertEquals(new BigDecimal("50.00"), captor.getValue().getMetricValue());
        assertEquals(WeeklyOptimizationReportType.IDLE_WASTE, captor.getValue().getReportType());
        assertEquals(env, captor.getValue().getEnvironment());
    }

    @Test
    void captureSnapshot_isIdempotent_updatesExistingRowInPlaceRatherThanDuplicating() {
        Environment env = new Environment("env-A");
        WeeklyOptimizationSnapshot existing = new WeeklyOptimizationSnapshot();
        existing.setEnvironment(env);
        existing.setReportType(WeeklyOptimizationReportType.IDLE_WASTE);
        existing.setSnapshotDate(snapshotDate);
        existing.setMetricValue(new BigDecimal("10.00"));

        when(snapshotRepository.findByEnvironment_EnvironmentIdAndReportTypeAndSnapshotDate(
                eq("env-A"), eq(WeeklyOptimizationReportType.IDLE_WASTE), eq(snapshotDate)))
                .thenReturn(Optional.of(existing));

        service.captureSnapshot(WeeklyOptimizationReportType.IDLE_WASTE, snapshotDate, Map.of("env-A", new BigDecimal("75.00")));

        verify(environmentRepository, never()).findById(any());
        ArgumentCaptor<WeeklyOptimizationSnapshot> captor = ArgumentCaptor.forClass(WeeklyOptimizationSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertSame(existing, captor.getValue(), "should update the existing row, not create a new one");
        assertEquals(new BigDecimal("75.00"), captor.getValue().getMetricValue());
    }
}
