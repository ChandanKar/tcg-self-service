package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.OperationTriggerSource;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.SavingsCalculator.BaselinePolicy;
import com.tcgdigital.vmcontrol.service.SavingsCalculator.Money;
import com.tcgdigital.vmcontrol.service.SavingsCalculator.TransitionWithCause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Savings math from the spec (E17-T02). */
class SavingsCalculatorTest {

    private final SavingsCalculator calc = new SavingsCalculator(24, "MON-SUN", new BigDecimal("0.005"));

    private static Instant at(LocalDate day, int hour) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(hour * 3600L);
    }

    @Test
    void aWeekdayTenHourScheduleSaves118HoursAndElevenEighty() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        BigDecimal hours = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            boolean weekday = day.getDayOfWeek().getValue() <= 5;
            List<TransitionWithCause> t = new ArrayList<>();
            if (weekday) {
                t.add(new TransitionWithCause(at(day, 8), VmStatus.RUNNING, SavingsCause.SCHEDULE));
                t.add(new TransitionWithCause(at(day, 18), VmStatus.STOPPED, SavingsCause.SCHEDULE));
            }
            Map<SavingsCause, BigDecimal> byCause = calc.avoidedByCause(at(day, 0), at(day.plusDays(1), 0),
                    VmStatus.STOPPED, SavingsCause.SCHEDULE, t, BaselinePolicy.always());
            assertThat(byCause.keySet()).containsOnly(SavingsCause.SCHEDULE);
            hours = hours.add(byCause.get(SavingsCause.SCHEDULE));
        }
        assertThat(hours).isEqualByComparingTo("118");
        Money money = calc.money(hours, new BigDecimal("0.10"), 0, null, false, null);
        assertThat(money.gross()).isEqualByComparingTo("11.80");
        assertThat(money.net()).isEqualByComparingTo("11.80");
    }

    @Test
    void residualStorageAndIpv4AreSubtracted() {
        // 118 h, 30 GiB at $0.08/GB-month, a public IP.
        Money money = calc.money(new BigDecimal("118"), new BigDecimal("0.10"), 30, new BigDecimal("0.08"), true, null);
        assertThat(money.residualStorage()).isEqualByComparingTo("0.39"); // 30*0.08/30 * 118/24
        assertThat(money.residualIpv4()).isEqualByComparingTo("0.59");    // 118 * 0.005
        assertThat(money.net()).isEqualByComparingTo("10.82");
    }

    @Test
    void fullCommitmentCoverageSavesNothingAndSaysWhy() {
        Money money = calc.money(new BigDecimal("10"), new BigDecimal("0.10"), 0, null, false, new BigDecimal("100"));
        assertThat(money.net()).isEqualByComparingTo("0");
        assertThat(money.commitmentAdjustment()).isEqualByComparingTo("1.00");
        assertThat(money.note()).contains("Savings Plans");
        assertThat(calc.money(new BigDecimal("10"), new BigDecimal("0.10"), 0, null, false, new BigDecimal("40")).net())
                .isEqualByComparingTo("0.60");
    }

    @Test
    void netNeverGoesBelowZero() {
        Money money = calc.money(new BigDecimal("1"), new BigDecimal("0.001"), 500, new BigDecimal("0.10"), true, null);
        assertThat(money.net()).isEqualByComparingTo("0");
    }

    @Test
    void anIntervalIsCreditedToTheCauseThatStartedIt() {
        LocalDate day = LocalDate.of(2026, 10, 7);
        List<TransitionWithCause> t = List.of(
                new TransitionWithCause(at(day, 14), VmStatus.STOPPED, SavingsCause.IDLE_STOP),
                new TransitionWithCause(at(day, 18), VmStatus.RUNNING, SavingsCause.MANUAL_STOP),
                new TransitionWithCause(at(day, 22), VmStatus.STOPPED, SavingsCause.MANUAL_STOP));
        Map<SavingsCause, BigDecimal> byCause = calc.avoidedByCause(at(day, 0), at(day.plusDays(1), 0),
                VmStatus.RUNNING, SavingsCause.OTHER, t, BaselinePolicy.always());
        assertThat(byCause.get(SavingsCause.IDLE_STOP)).isEqualByComparingTo("4");
        assertThat(byCause.get(SavingsCause.MANUAL_STOP)).isEqualByComparingTo("2");
    }

    @Test
    void aWeekdayOnlyBaselineSavesNothingAtWeekends() {
        BaselinePolicy weekdays = BaselinePolicy.parse(24, "MON-FRI");
        assertThat(weekdays.days()).isEqualTo(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        assertThat(calc.avoidedByCause(at(saturday, 0), at(saturday.plusDays(1), 0), VmStatus.STOPPED,
                SavingsCause.SCHEDULE, List.of(), weekdays)).isEmpty();
        // A 10-hour baseline counts only the first 10 hours of an included day.
        LocalDate monday = LocalDate.of(2026, 10, 5);
        assertThat(calc.avoidedByCause(at(monday, 0), at(monday.plusDays(1), 0), VmStatus.STOPPED,
                SavingsCause.SCHEDULE, List.of(), BaselinePolicy.parse(10, "MON,TUE")).get(SavingsCause.SCHEDULE))
                .isEqualByComparingTo("10");
    }

    @Test
    void causesMapFromTriggerSources() {
        assertThat(SavingsCause.fromTrigger(OperationTriggerSource.SCHEDULE)).isEqualTo(SavingsCause.SCHEDULE);
        assertThat(SavingsCause.fromTrigger(OperationTriggerSource.LEASE)).isEqualTo(SavingsCause.LEASE);
        assertThat(SavingsCause.fromTrigger(OperationTriggerSource.IDLE_STOP)).isEqualTo(SavingsCause.IDLE_STOP);
        assertThat(SavingsCause.fromTrigger(OperationTriggerSource.MANUAL)).isEqualTo(SavingsCause.MANUAL_STOP);
        assertThat(SavingsCause.fromTrigger(OperationTriggerSource.BUDGET)).isEqualTo(SavingsCause.OTHER);
        assertThat(SavingsCause.fromTrigger(null)).isEqualTo(SavingsCause.OTHER);
    }
}
