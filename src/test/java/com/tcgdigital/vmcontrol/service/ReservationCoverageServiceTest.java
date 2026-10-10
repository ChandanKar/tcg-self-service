package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ReservationCoverageSnapshot;
import com.tcgdigital.vmcontrol.repository.ReservationCoverageSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.SavingsPlansCoverage;
import software.amazon.awssdk.services.costexplorer.model.SavingsPlansCoverageData;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily coverage row describes yesterday and records the spend covered by Savings Plans
 * (E08-T08).
 */
@ExtendWith(MockitoExtension.class)
class ReservationCoverageServiceTest {

    @Mock private ReservationCoverageSnapshotRepository repository;
    @Mock private CostExplorerClient ce;

    @Test
    void coveredCostIsParsedAndTheRowIsDatedYesterday() {
        CostDayBoundary boundary = new CostDayBoundary("UTC",
                Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneOffset.UTC));
        ReservationCoverageService service = new ReservationCoverageService(repository, new CostExplorerClientProvider(ce), boundary);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(repository.findBySnapshotDate(any())).thenReturn(Optional.empty());
        when(ce.getSavingsPlansCoverage(any(GetSavingsPlansCoverageRequest.class))).thenReturn(
                GetSavingsPlansCoverageResponse.builder().savingsPlansCoverages(SavingsPlansCoverage.builder()
                        .coverage(SavingsPlansCoverageData.builder()
                                .coveragePercentage("61.5").spendCoveredBySavingsPlans("12.34").build())
                        .build()).build());

        service.captureDailySnapshot();

        ArgumentCaptor<ReservationCoverageSnapshot> saved = ArgumentCaptor.forClass(ReservationCoverageSnapshot.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSnapshotDate()).isEqualTo(Date.valueOf("2026-10-06"));
        assertThat(saved.getValue().getCoveredCost()).isEqualByComparingTo("12.34");
        assertThat(saved.getValue().getSpCoveragePercent()).isEqualByComparingTo("61.5");

        ArgumentCaptor<GetSavingsPlansCoverageRequest> request = ArgumentCaptor.forClass(GetSavingsPlansCoverageRequest.class);
        verify(ce).getSavingsPlansCoverage(request.capture());
        assertThat(request.getValue().timePeriod().start()).isEqualTo("2026-10-06");
        assertThat(request.getValue().timePeriod().end()).isEqualTo("2026-10-07");
    }
}
