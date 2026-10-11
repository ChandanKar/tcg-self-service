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

    /** One window of a rule: its days and optional start and stop times (E19-T04). */
    public record WindowSpec(Set<DayOfWeek> days, LocalTime start, LocalTime stop) {}

    /** An upcoming START or STOP; {@code skipReason} is null when it runs. */
    public record Run(java.time.ZonedDateTime at, com.tcgdigital.vmcontrol.model.OperationType action, int windowIndex,
                      String skipReason) {
        public boolean skipped() {
            return skipReason != null;
        }
    }

    /** Why a run would be skipped (an override, a holiday), or empty. */
    @FunctionalInterface
    public interface SkipLookup {
        java.util.Optional<String> reason(com.tcgdigital.vmcontrol.model.OperationType action, LocalDate day,
                                          java.time.Instant at);
    }

    /** Days looked ahead by {@link #nextRuns}. */
    public static final int PREVIEW_DAYS = 60;

    /**
     * The first {@code limit} runs strictly after {@code from}, in time order (STOP before START at
     * the same instant), over up to {@link #PREVIEW_DAYS} days in {@code zone}. Each time uses the
     * same DST handling as {@link #dueInstant}; skipped runs are included and marked.
     */
    public java.util.List<Run> nextRuns(java.util.List<WindowSpec> windows, java.time.ZoneId zone, java.time.Instant from,
                                        int limit, SkipLookup skips) {
        java.util.List<Run> runs = new java.util.ArrayList<>();
        LocalDate first = from.atZone(zone).toLocalDate();
        for (int offset = 0; offset < PREVIEW_DAYS; offset++) {
            LocalDate day = first.plusDays(offset);
            for (int i = 0; i < windows.size(); i++) {
                WindowSpec w = windows.get(i);
                if (!w.days().contains(day.getDayOfWeek())) {
                    continue;
                }
                addRun(runs, w.stop(), com.tcgdigital.vmcontrol.model.OperationType.STOP, day, i, zone, from, skips);
                addRun(runs, w.start(), com.tcgdigital.vmcontrol.model.OperationType.START, day, i, zone, from, skips);
            }
            // Later days only add later runs.
            if (runs.size() >= limit) {
                break;
            }
        }
        runs.sort(java.util.Comparator.comparing((Run r) -> r.at().toInstant())
                .thenComparing(r -> r.action() == com.tcgdigital.vmcontrol.model.OperationType.STOP ? 0 : 1));
        return runs.size() > limit ? java.util.List.copyOf(runs.subList(0, limit)) : runs;
    }

    private void addRun(java.util.List<Run> runs, LocalTime time, com.tcgdigital.vmcontrol.model.OperationType action,
                        LocalDate day, int window, java.time.ZoneId zone, java.time.Instant from, SkipLookup skips) {
        if (time == null) {
            return;
        }
        ZonedDateTime at = ZonedDateTime.of(day, time, zone);
        if (!at.toInstant().isAfter(from)) {
            return;
        }
        runs.add(new Run(at, action, window, skips.reason(action, day, at.toInstant()).orElse(null)));
    }

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

    /**
     * The first instant after {@code from} at which {@code time} falls on one of {@code days} in
     * {@code zone} (looks up to 8 days ahead); empty when no day is selected. Same DST handling as
     * {@link #dueInstant}.
     */
    public java.util.Optional<java.time.Instant> nextFiring(Set<DayOfWeek> days, LocalTime time,
                                                           java.time.ZoneId zone, java.time.Instant from) {
        if (days.isEmpty() || time == null) {
            return java.util.Optional.empty();
        }
        ZonedDateTime start = from.atZone(zone);
        for (int offset = 0; offset <= 8; offset++) {
            LocalDate day = start.toLocalDate().plusDays(offset);
            if (!days.contains(day.getDayOfWeek())) {
                continue;
            }
            ZonedDateTime at = dueInstant(day, time, start);
            if (at.toInstant().isAfter(from)) {
                return java.util.Optional.of(at.toInstant());
            }
        }
        return java.util.Optional.empty();
    }
}
