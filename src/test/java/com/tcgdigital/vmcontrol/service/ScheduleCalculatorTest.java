package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Due-time logic for schedule rules (E06-T03, H26, LOW-AUTO-DST).
 */
class ScheduleCalculatorTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);

    private final ScheduleCalculator calc = new ScheduleCalculator();

    private static ZonedDateTime at(LocalDate day, int h, int m, int s, ZoneId zone) {
        return ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(h, m, s)), zone);
    }

    @Test
    void dueAtTheMinuteAndOnALateTickInsideTheWindow() {
        assertThat(calc.isDue(at(WEDNESDAY, 20, 0, 40, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isTrue();
        assertThat(calc.isDue(at(WEDNESDAY, 20, 3, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isTrue();
        assertThat(calc.isDue(at(WEDNESDAY, 20, 14, 59, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isTrue();
    }

    @Test
    void notDueBeforeTheTimeOrAfterTheWindow() {
        assertThat(calc.isDue(at(WEDNESDAY, 19, 59, 59, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isFalse();
        assertThat(calc.isDue(at(WEDNESDAY, 20, 15, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isFalse();
        assertThat(calc.isMissed(at(WEDNESDAY, 20, 20, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isTrue();
        assertThat(calc.isMissed(at(WEDNESDAY, 20, 5, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isFalse();
    }

    @Test
    void notDueWhenAlreadyFiredTodayOrOnAnUnselectedDay() {
        assertThat(calc.isDue(at(WEDNESDAY, 20, 1, 0, KOLKATA), EIGHT_PM, WEEKDAYS, WEDNESDAY, WINDOW)).isFalse();
        assertThat(calc.isMissed(at(WEDNESDAY, 21, 0, 0, KOLKATA), EIGHT_PM, WEEKDAYS, WEDNESDAY, WINDOW)).isFalse();
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        assertThat(calc.isDue(at(saturday, 20, 0, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isFalse();
        assertThat(calc.isMissed(at(saturday, 21, 0, 0, KOLKATA), EIGHT_PM, WEEKDAYS, null, WINDOW)).isFalse();
        // Yesterday's fire does not count for today.
        assertThat(calc.isDue(at(WEDNESDAY, 20, 0, 0, KOLKATA), EIGHT_PM, WEEKDAYS, WEDNESDAY.minusDays(1), WINDOW)).isTrue();
    }

    @Test
    void aTimeInsideTheSpringForwardGapFiresAfterTheGap() {
        LocalDate springForward = LocalDate.of(2026, 3, 29); // Europe/London 01:00 -> 02:00
        LocalTime halfPastOne = LocalTime.of(1, 30);
        Set<DayOfWeek> sunday = EnumSet.of(DayOfWeek.SUNDAY);

        ZonedDateTime due = calc.dueInstant(springForward, halfPastOne, at(springForward, 3, 0, 0, LONDON));
        assertThat(due.toLocalTime()).isEqualTo(LocalTime.of(2, 30));
        assertThat(calc.isDue(at(springForward, 2, 30, 10, LONDON), halfPastOne, sunday, null, WINDOW)).isTrue();
        assertThat(calc.isDue(at(springForward, 0, 59, 0, LONDON), halfPastOne, sunday, null, WINDOW)).isFalse();
    }

    @Test
    void aTimeInsideTheAutumnOverlapUsesTheEarlierOffset() {
        LocalDate fallBack = LocalDate.of(2026, 10, 25); // Europe/London 02:00 -> 01:00
        LocalTime halfPastOne = LocalTime.of(1, 30);

        ZonedDateTime due = calc.dueInstant(fallBack, halfPastOne, at(fallBack, 0, 0, 0, LONDON));
        assertThat(due.getOffset().getTotalSeconds()).isEqualTo(3600); // BST, the first 01:30
    }

    @Test
    void parsesThreeLetterDayNamesInAnyCase() {
        assertThat(calc.parseDays("MON, wed,Fri")).containsExactlyInAnyOrder(
                DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        assertThat(calc.parseDays(null)).isEmpty();
        assertThat(calc.parseDays("XX,M")).isEmpty();
    }
}
