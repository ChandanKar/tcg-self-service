package com.tcgdigital.vmcontrol.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * When a schedule rule is due (C6, H26, LOW-AUTO-DST). Pure: callers pass "now", so the logic is
 * testable without a clock.
 *
 * <p>A time is due from its instant on the given day until {@code catchUp} later, so a late
 * scheduler tick still fires it. A time inside a DST gap (02:30 on spring-forward day) moves
 * forward by the gap (03:30); in an overlap the earlier offset is used. A window that would run
 * past midnight ends at midnight.
 */
@Component
public class ScheduleCalculator {

    /** The instant {@code time} happens on {@code day} in {@code now}'s zone. */
    public ZonedDateTime dueInstant(LocalDate day, LocalTime time, ZonedDateTime now) {
        return ZonedDateTime.of(day, time, now.getZone());
    }

    /** True when today is selected, not yet fired today, and now is within [due, due + catchUp). */
    public boolean isDue(ZonedDateTime now, LocalTime time, Set<DayOfWeek> days, LocalDate lastFiredOn,
                         Duration catchUp) {
        LocalDate today = now.toLocalDate();
        if (!days.contains(today.getDayOfWeek()) || today.equals(lastFiredOn)) {
            return false;
        }
        ZonedDateTime due = dueInstant(today, time, now);
        return !now.isBefore(due) && now.isBefore(due.plus(catchUp));
    }

    /** True when today's window has fully passed without a successful fire. */
    public boolean isMissed(ZonedDateTime now, LocalTime time, Set<DayOfWeek> days, LocalDate lastFiredOn,
                            Duration catchUp) {
        LocalDate today = now.toLocalDate();
        if (!days.contains(today.getDayOfWeek()) || today.equals(lastFiredOn)) {
            return false;
        }
        return !now.isBefore(dueInstant(today, time, now).plus(catchUp));
    }

    /** "MON,WED,FRI" (three-letter English names, any case) to day set; unknown names ignored. */
    public Set<DayOfWeek> parseDays(String daysOfWeek) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (daysOfWeek == null) {
            return days;
        }
        for (String token : daysOfWeek.split(",")) {
            String abbrev = token.trim().toUpperCase(Locale.ROOT);
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().startsWith(abbrev) && abbrev.length() >= 3) {
                    days.add(day);
                }
            }
        }
        return days;
    }
}
