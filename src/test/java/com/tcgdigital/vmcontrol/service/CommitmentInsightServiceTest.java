package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CommitmentInsightDTO;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import com.tcgdigital.vmcontrol.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The schedule-safe commitment floor, the risk flags and empty-data handling (E27-T02). */
class CommitmentInsightServiceTest {

    private static final Instant END = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant START = END.minus(30, ChronoUnit.DAYS);

    private static CommitmentInsightService.RunningVm alwaysOn(String rate) {
        return new CommitmentInsightService.RunningVm(new BigDecimal(rate),
                List.of(new CommitmentInsightService.Interval(START.minus(1, ChronoUnit.DAYS), END)));
    }

    /** Runs 09:00-17:00 UTC on weekdays only. */
    private static CommitmentInsightService.RunningVm officeHours(String rate) {
        List<CommitmentInsightService.Interval> runs = new ArrayList<>();
        for (LocalDate d = LocalDate.ofInstant(START, ZoneOffset.UTC); d.isBefore(LocalDate.ofInstant(END, ZoneOffset.UTC)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            runs.add(new CommitmentInsightService.Interval(d.atTime(9, 0).toInstant(ZoneOffset.UTC), d.atTime(17, 0).toInstant(ZoneOffset.UTC)));
        }
        return new CommitmentInsightService.RunningVm(new BigDecimal(rate), runs);
    }

    @Test
    void theFloorIsTheSpendOfTheQuietestHoursOnly() {
        List<CommitmentInsightService.RunningVm> vms = new ArrayList<>();
        for (int i = 0; i < 20; i++) vms.add(alwaysOn("0.10"));
        for (int i = 0; i < 30; i++) vms.add(officeHours("0.10"));

        assertThat(CommitmentInsightService.hourlyFloor(vms, START, END)).isEqualByComparingTo("2.00");
    }

    @Test
    void partHoursCountForTheTimeRunning() {
        // Half of every hour: 0.5 x $1.00.
        List<CommitmentInsightService.Interval> halves = new ArrayList<>();
        for (Instant h = START; h.isBefore(END); h = h.plus(1, ChronoUnit.HOURS)) {
            halves.add(new CommitmentInsightService.Interval(h, h.plus(30, ChronoUnit.MINUTES)));
        }
        assertThat(CommitmentInsightService.hourlyFloor(
                List.of(new CommitmentInsightService.RunningVm(BigDecimal.ONE, halves)), START, END)).isEqualByComparingTo("0.50");
    }

    @Test
    void noPricedVmsGiveNoFloor() {
        assertThat(CommitmentInsightService.hourlyFloor(List.of(), START, END)).isNull();
    }

    private static CommitmentInsightDTO.Recommendation sp(String type, String hourly) {
        return new CommitmentInsightDTO.Recommendation(type, "ONE_YEAR", "NO_UPFRONT", 30, new BigDecimal(hourly), null,
                null, null, null, LocalDate.of(2026, 10, 12), null);
    }

    private static CommitmentInsightDTO.Coverage coverage(String spCoverage, String spUtil, String riUtil) {
        return new CommitmentInsightDTO.Coverage(LocalDate.of(2026, 10, 11), null,
                spCoverage == null ? null : new BigDecimal(spCoverage), riUtil == null ? null : new BigDecimal(riUtil),
                spUtil == null ? null : new BigDecimal(spUtil));
    }

    @Test
    void aRecommendationAboveTheFloorIsFlaggedAsAnOverCommit() {
        List<CommitmentInsightDTO.RiskFlag> flags = CommitmentInsightService.riskFlags(
                List.of(sp("COMPUTE_SP", "3.50"), sp("EC2_INSTANCE_SP", "2.10")), null, new BigDecimal("2.00"), null);

        assertThat(flags).extracting(CommitmentInsightDTO.RiskFlag::code, CommitmentInsightDTO.RiskFlag::recommendationType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVER_COMMIT", "COMPUTE_SP")); // 2.10 is within 10%
        assertThat(flags.get(0).message()).contains("exceeds the always-on floor");
    }

    @Test
    void lowUtilisationAndCoveredSchedulesAreFlagged() {
        List<CommitmentInsightDTO.RiskFlag> flags = CommitmentInsightService.riskFlags(List.of(),
                coverage("85", "88", "95"), null, new BigDecimal("120"));

        assertThat(flags).extracting(CommitmentInsightDTO.RiskFlag::code).containsExactly("UNDER_UTILISED", "SCHEDULES_ALREADY_COVERED");
        assertThat(flags.get(0).message()).contains("Savings Plans").doesNotContain("Reserved Instances");

        assertThat(CommitmentInsightService.riskFlags(List.of(), coverage("85", "95", null), null, new BigDecimal("50"))).isEmpty();
        assertThat(CommitmentInsightService.riskFlags(List.of(), coverage("60", null, null), null, new BigDecimal("500"))).isEmpty();
    }

    private CommitmentInsightService service(List<SavingsLedgerEntry> ledger) {
        SavingsLedgerEntryRepository ledgerRepository = mock(SavingsLedgerEntryRepository.class);
        when(ledgerRepository.findByLedgerDateBetweenOrderByLedgerDateAsc(any(), any())).thenReturn(ledger);
        ReservationCoverageSnapshotRepository coverageRepository = mock(ReservationCoverageSnapshotRepository.class);
        when(coverageRepository.findTopByOrderBySnapshotDateDesc()).thenReturn(Optional.empty());
        CommitmentRecommendationRepository recommendations = mock(CommitmentRecommendationRepository.class);
        when(recommendations.findLatest()).thenReturn(List.of());
        VmRepository vms = mock(VmRepository.class);
        when(vms.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of());
        VmInventorySnapshotRepository inventory = mock(VmInventorySnapshotRepository.class);
        when(inventory.findInstanceTypesByVmIds(anyList())).thenReturn(List.of());
        CommitmentInsightService service = new CommitmentInsightService(ledgerRepository, coverageRepository, recommendations, vms,
                mock(VmStateHistoryRepository.class), inventory, new PricingReferenceService());
        service.clock = Clock.fixed(END, ZoneOffset.UTC);
        return service;
    }

    private static SavingsLedgerEntry entry(SavingsCause cause, String hours, String gross, String adjustment) {
        SavingsLedgerEntry e = new SavingsLedgerEntry();
        e.setCause(cause);
        e.setAvoidedHours(new BigDecimal(hours));
        e.setGrossSaving(new BigDecimal(gross));
        e.setCommitmentAdjustment(new BigDecimal(adjustment));
        return e;
    }

    @Test
    void ledgerCommitmentAdjustmentsAreTheValueAlreadyCovered() {
        CommitmentInsightDTO dto = service(List.of(entry(SavingsCause.SCHEDULE, "60", "200", "80"),
                entry(SavingsCause.IDLE_STOP, "10", "50", "40"))).insights();

        assertThat(dto.scheduledHoursCoveredValue()).isEqualByComparingTo("120");
        assertThat(dto.grossScheduledSaving()).isEqualByComparingTo("250");
        assertThat(dto.scheduledAvoidedHours()).isEqualByComparingTo("60");
    }

    @Test
    void emptyDataGivesNullsNotZeros() {
        CommitmentInsightDTO dto = service(List.of()).insights();

        assertThat(dto.scheduledHoursCoveredValue()).isNull();
        assertThat(dto.grossScheduledSaving()).isNull();
        assertThat(dto.alwaysOnHourlyFloor()).isNull();
        assertThat(dto.coverage()).isNull();
        assertThat(dto.recommendations()).isEmpty();
        assertThat(dto.riskFlags()).isEmpty();
        assertThat(dto.floorNote()).contains("EKS");
    }
}
