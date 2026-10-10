package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily cost snapshot covers complete UTC days ending yesterday (E08-T01, H7): the 03:00
 * run recaptures D-3..D-1 with UTC bounds whatever the server timezone, backfill never writes
 * today, and an existing row for a date is updated rather than duplicated.
 */
@ExtendWith(MockitoExtension.class)
class CostSnapshotServiceTest {

    // 2026-10-07 03:00 UTC (08:30 in Asia/Kolkata): the scheduled run.
    private static final Instant RUN_AT = Instant.parse("2026-10-07T03:00:00Z");

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private VmRepository vmRepository;
    @Mock private CostDataProvider costDataProvider;
    @Mock private CostDailySnapshotRepository snapshotRepository;
    @Mock private CostEstimationService costEstimationService;

    private Environment env;

    @BeforeEach
    void setUp() {
        env = new Environment();
        env.setEnvironmentId("env-1");
        when(environmentRepository.findByIsActiveTrue()).thenReturn(List.of(env));
        when(vmRepository.findByEnvironmentIdIn(any())).thenReturn(List.of());
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenReturn(Map.of());
    }

    private CostSnapshotService service(String zone, int recomputeDays) {
        CostDayBoundary boundary = new CostDayBoundary(zone, Clock.fixed(RUN_AT, ZoneId.of("Asia/Kolkata")));
        return new CostSnapshotService(environmentRepository, vmRepository, costDataProvider, snapshotRepository,
                boundary, costEstimationService, recomputeDays);
    }

    private List<Date> savedDates(int expected) {
        ArgumentCaptor<CostDailySnapshot> saved = ArgumentCaptor.forClass(CostDailySnapshot.class);
        verify(snapshotRepository, times(expected)).save(saved.capture());
        return saved.getAllValues().stream().map(CostDailySnapshot::getSnapshotDate).toList();
    }

    @Test
    void theDailyRunRecapturesTheLastThreeCompleteUtcDaysAndNotToday() {
        when(snapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any())).thenReturn(Optional.empty());

        service("UTC", 3).captureDailySnapshot();

        assertThat(savedDates(3)).containsExactly(Date.valueOf("2026-10-04"), Date.valueOf("2026-10-05"),
                Date.valueOf("2026-10-06"));
        verify(snapshotRepository, never()).findByEnvironmentEnvironmentIdAndSnapshotDate("env-1", Date.valueOf("2026-10-07"));
    }

    @Test
    void eachDayIsAWholeUtcDayEvenOnAServerInKolkata() {
        when(snapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any())).thenReturn(Optional.empty());

        service("UTC", 1).captureDailySnapshot();

        verify(costDataProvider).estimateCosts(anyList(), eq(Timestamp.from(Instant.parse("2026-10-06T00:00:00Z"))),
                eq(Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"))));
    }

    @Test
    void backfillEndsYesterday() {
        when(snapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any())).thenReturn(Optional.empty());

        service("UTC", 3).backfillHistoricalSnapshots(30);

        List<Date> dates = savedDates(30);
        assertThat(dates.get(0)).isEqualTo(Date.valueOf(LocalDate.parse("2026-09-07")));
        assertThat(dates.get(29)).isEqualTo(Date.valueOf("2026-10-06"));
        assertThat(dates).doesNotContain(Date.valueOf("2026-10-07"));
        verify(costEstimationService).invalidateBundles(); // E08-T04: the cost page rebuilds
    }

    @Test
    void anExistingRowForADateIsUpdatedNotDuplicated() {
        CostDailySnapshot existing = new CostDailySnapshot();
        when(snapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDate(any(), any())).thenReturn(Optional.of(existing));

        service("UTC", 1).captureDailySnapshot();

        verify(snapshotRepository).save(existing);
        assertThat(existing.getSnapshotDate()).isEqualTo(Date.valueOf("2026-10-06"));
    }
}
