package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CommitmentRecommendation;
import com.tcgdigital.vmcontrol.repository.CommitmentRecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Savings Plan and Reserved Instance purchase recommendations from Cost Explorer (E27-T01), with a mocked client. */
class CommitmentRecommendationServiceTest {

    private static final Instant MONDAY = Instant.parse("2026-10-12T05:15:00Z");

    private CostExplorerClient ce;
    private CommitmentRecommendationRepository repository;
    private CommitmentRecommendationService service;

    @BeforeEach
    void setUp() {
        ce = mock(CostExplorerClient.class);
        repository = mock(CommitmentRecommendationRepository.class);
        when(repository.findByGeneratedOnAndRecTypeAndTermAndPaymentOption(any(), any(), any(), any())).thenReturn(Optional.empty());
        CostDayBoundary boundary = new CostDayBoundary("UTC", Clock.fixed(MONDAY, ZoneOffset.UTC));
        service = new CommitmentRecommendationService(repository, new CostExplorerClientProvider(ce), boundary);
    }

    private static SavingsPlansPurchaseRecommendationDetail spDetail(String family, String savings) {
        return SavingsPlansPurchaseRecommendationDetail.builder()
                .savingsPlansDetails(SavingsPlansDetails.builder().instanceFamily(family).region("ap-south-1").build())
                .hourlyCommitmentToPurchase("0.40").estimatedMonthlySavingsAmount(savings).upfrontCost("10").build();
    }

    private static ReservationPurchaseRecommendationDetail riDetail(String type, String savings) {
        return ReservationPurchaseRecommendationDetail.builder()
                .instanceDetails(InstanceDetails.builder().ec2InstanceDetails(EC2InstanceDetails.builder()
                        .family(type.substring(0, type.indexOf('.'))).instanceType(type).region("ap-south-1").build()).build())
                .recommendedNumberOfInstancesToPurchase("2").estimatedMonthlySavingsAmount(savings)
                .estimatedMonthlyOnDemandCost("100").upfrontCost("0").build();
    }

    /** Compute SP: one page with the summary; EC2 Instance SP: two pages, the summary on the first. */
    private void savingsPlans() {
        when(ce.getSavingsPlansPurchaseRecommendation(any(GetSavingsPlansPurchaseRecommendationRequest.class))).thenAnswer(inv -> {
            GetSavingsPlansPurchaseRecommendationRequest r = inv.getArgument(0);
            boolean compute = r.savingsPlansType() == SupportedSavingsPlansType.COMPUTE_SP;
            if (compute) {
                return GetSavingsPlansPurchaseRecommendationResponse.builder().savingsPlansPurchaseRecommendation(
                        SavingsPlansPurchaseRecommendation.builder()
                                .savingsPlansPurchaseRecommendationSummary(SavingsPlansPurchaseRecommendationSummary.builder()
                                        .hourlyCommitmentToPurchase("1.20").estimatedMonthlySavingsAmount("300")
                                        .estimatedSavingsPercentage("25").currentOnDemandSpend("1200").build())
                                .savingsPlansPurchaseRecommendationDetails(spDetail("m5", "200"), spDetail("c5", "100")).build()).build();
            }
            boolean second = "p2".equals(r.nextPageToken());
            return GetSavingsPlansPurchaseRecommendationResponse.builder()
                    .nextPageToken(second ? null : "p2")
                    .savingsPlansPurchaseRecommendation(SavingsPlansPurchaseRecommendation.builder()
                            .savingsPlansPurchaseRecommendationSummary(second ? null : SavingsPlansPurchaseRecommendationSummary.builder()
                                    .hourlyCommitmentToPurchase("0.80").estimatedMonthlySavingsAmount("150").build())
                            .savingsPlansPurchaseRecommendationDetails(second ? spDetail("r5", "90") : spDetail("t3", "60")).build())
                    .build();
        });
    }

    private Map<CommitmentRecommendation.Type, CommitmentRecommendation> saved(int count) {
        ArgumentCaptor<CommitmentRecommendation> rows = ArgumentCaptor.forClass(CommitmentRecommendation.class);
        verify(repository, times(count)).save(rows.capture());
        return rows.getAllValues().stream().collect(Collectors.toMap(CommitmentRecommendation::getRecType, Function.identity()));
    }

    @Test
    void savingsPlanSummariesAndPagedDetailsAreStored() throws Exception {
        savingsPlans();
        when(ce.getReservationPurchaseRecommendation(any(GetReservationPurchaseRecommendationRequest.class)))
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());

        assertThat(service.capture()).isEqualTo(3);

        Map<CommitmentRecommendation.Type, CommitmentRecommendation> rows = saved(3);
        CommitmentRecommendation compute = rows.get(CommitmentRecommendation.Type.COMPUTE_SP);
        assertThat(compute.getHourlyCommitment()).isEqualByComparingTo("1.20");
        assertThat(compute.getEstimatedMonthlySavings()).isEqualByComparingTo("300");
        assertThat(compute.getEstimatedSavingsPercent()).isEqualByComparingTo("25");
        assertThat(compute.getEstimatedOnDemandCost()).isEqualByComparingTo("1200");
        assertThat(compute.getUpfrontCost()).isEqualByComparingTo("20");
        assertThat(compute.getGeneratedOn()).isEqualTo(Date.valueOf("2026-10-12"));
        assertThat(compute.getTerm()).isEqualTo("ONE_YEAR");
        assertThat(compute.getPaymentOption()).isEqualTo("NO_UPFRONT");
        assertThat(compute.getLookbackDays()).isEqualTo(30);
        assertThat(new ObjectMapper().readTree(compute.getDetails()).get(0).get("instanceFamily").asText()).isEqualTo("m5");

        CommitmentRecommendation instance = rows.get(CommitmentRecommendation.Type.EC2_INSTANCE_SP);
        assertThat(instance.getHourlyCommitment()).isEqualByComparingTo("0.80");
        JsonNode details = new ObjectMapper().readTree(instance.getDetails());
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("instanceFamily").asText()).isEqualTo("r5"); // second page, larger saving first

        // An account with no RI recommendation stores an empty row rather than invented numbers.
        assertThat(rows.get(CommitmentRecommendation.Type.EC2_RI).getEstimatedMonthlySavings()).isNull();
    }

    @Test
    void reservationDetailsAreSummedWithTheTopTenKept() throws Exception {
        savingsPlans();
        List<ReservationPurchaseRecommendationDetail> details = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> riDetail("m5.large", String.valueOf(i * 10))).toList();
        when(ce.getReservationPurchaseRecommendation(any(GetReservationPurchaseRecommendationRequest.class))).thenAnswer(inv -> {
            GetReservationPurchaseRecommendationRequest r = inv.getArgument(0);
            assertThat(r.service()).isEqualTo(CommitmentRecommendationService.EC2_SERVICE);
            boolean second = "n2".equals(r.nextPageToken());
            return GetReservationPurchaseRecommendationResponse.builder()
                    .nextPageToken(second ? null : "n2")
                    .recommendations(ReservationPurchaseRecommendation.builder()
                            .recommendationDetails(second ? details.subList(6, 12) : details.subList(0, 6)).build())
                    .build();
        });

        service.capture();

        CommitmentRecommendation ri = saved(3).get(CommitmentRecommendation.Type.EC2_RI);
        assertThat(ri.getEstimatedMonthlySavings()).isEqualByComparingTo("780"); // 10 + 20 + ... + 120
        assertThat(ri.getEstimatedOnDemandCost()).isEqualByComparingTo("1200");
        assertThat(ri.getEstimatedSavingsPercent()).isEqualByComparingTo("65");
        assertThat(ri.getHourlyCommitment()).isNull();
        JsonNode top = new ObjectMapper().readTree(ri.getDetails());
        assertThat(top).hasSize(10);
        assertThat(top.get(0).get("estimatedMonthlySavings").asDouble()).isEqualTo(120.0);
        assertThat(top.get(0).get("instanceType").asText()).isEqualTo("m5.large");
        verify(ce, times(2)).getReservationPurchaseRecommendation(any(GetReservationPurchaseRecommendationRequest.class));
    }

    @Test
    void aDeniedReservationCallLeavesTheSavingsPlanRows() {
        savingsPlans();
        when(ce.getReservationPurchaseRecommendation(any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(CostExplorerException.builder().message("not authorized to perform: ce:GetReservationPurchaseRecommendation").build());

        assertThat(service.capture()).isEqualTo(2);

        assertThat(saved(2)).containsOnlyKeys(CommitmentRecommendation.Type.COMPUTE_SP, CommitmentRecommendation.Type.EC2_INSTANCE_SP);
    }

    @Test
    void aSecondCaptureTheSameDayUpdatesTheRow() {
        savingsPlans();
        when(ce.getReservationPurchaseRecommendation(any(GetReservationPurchaseRecommendationRequest.class)))
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());
        CommitmentRecommendation existing = new CommitmentRecommendation();
        existing.setRecId("rec-1");
        existing.setRecType(CommitmentRecommendation.Type.COMPUTE_SP);
        when(repository.findByGeneratedOnAndRecTypeAndTermAndPaymentOption(Date.valueOf("2026-10-12"),
                CommitmentRecommendation.Type.COMPUTE_SP, "ONE_YEAR", "NO_UPFRONT")).thenReturn(Optional.of(existing));

        service.capture();

        assertThat(saved(3).get(CommitmentRecommendation.Type.COMPUTE_SP)).isSameAs(existing);
        assertThat(existing.getHourlyCommitment()).isEqualByComparingTo("1.20");
    }

    @Test
    void withoutCredentialsNothingIsCalled() {
        CommitmentRecommendationService off = new CommitmentRecommendationService(repository,
                new CostExplorerClientProvider("", ""), new CostDayBoundary("UTC", Clock.fixed(MONDAY, ZoneOffset.UTC)));
        assertThat(off.capture()).isZero();
        verifyNoInteractions(ce);
        verify(repository, never()).save(any());
    }
}
