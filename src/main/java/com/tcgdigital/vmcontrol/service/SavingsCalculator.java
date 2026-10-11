package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure savings math (E17-T02), no DB access — like {@link VmCostCalculator}. A VM's avoided hours
 * on a day are the hours it was not running (STOPPED or STOPPING) inside its baseline — what it
 * would have run without the platform (24x7 by default, the AWS Instance Scheduler convention) —
 * credited to the cause of the transition that stopped it. Money: gross = hours x On-Demand rate,
 * minus what a stopped VM still pays (EBS, public IPv4) and the share commitments already covered.
 */
@Component
public class SavingsCalculator {

    private static final BigDecimal MILLIS_PER_HOUR = BigDecimal.valueOf(3_600_000L);
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final String FULLY_COVERED_NOTE = "Compute covered by Savings Plans/RIs; stopping does not reduce the bill";

    /** A state change with what caused it. */
    public record TransitionWithCause(Instant at, VmStatus newStatus, SavingsCause cause) {
    }

    /** Which hours of a day the VM would have run without the platform: the first {@code hoursPerDay} (UTC) of {@code days}. */
    public record BaselinePolicy(int hoursPerDay, Set<DayOfWeek> days) {
        public static BaselinePolicy always() {
            return new BaselinePolicy(24, EnumSet.allOf(DayOfWeek.class));
        }

        /** "MON-SUN", "MON-FRI" or "MON,WED,FRI". */
        public static BaselinePolicy parse(int hoursPerDay, String days) {
            Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
            for (String part : days.toUpperCase(Locale.ROOT).split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.contains("-")) {
                    DayOfWeek from = day(p.substring(0, p.indexOf('-')));
                    DayOfWeek to = day(p.substring(p.indexOf('-') + 1));
                    DayOfWeek d = from;
                    while (true) {
                        set.add(d);
                        if (d == to) break;
                        d = d.plus(1);
                    }
                } else {
                    set.add(day(p));
                }
            }
            return new BaselinePolicy(Math.max(0, Math.min(24, hoursPerDay)), set);
        }

        private static DayOfWeek day(String s) {
            for (DayOfWeek d : DayOfWeek.values()) {
                if (d.name().startsWith(s.trim())) return d;
            }
            throw new IllegalArgumentException("Unknown day: " + s);
        }
    }

    /** The money for one VM, day and cause. */
    public record Money(BigDecimal gross, BigDecimal residualStorage, BigDecimal residualIpv4,
                        BigDecimal commitmentAdjustment, BigDecimal net, String note) {
    }

    private final BaselinePolicy defaultBaseline;
    private final BigDecimal ipv4HourlyRate;

    public SavingsCalculator(@Value("${savings.baseline.hours-per-day:24}") int hoursPerDay,
                             @Value("${savings.baseline.days:MON-SUN}") String days,
                             @Value("${savings.ipv4-hourly-rate:0.005}") BigDecimal ipv4HourlyRate) {
        this.defaultBaseline = BaselinePolicy.parse(hoursPerDay, days);
        this.ipv4HourlyRate = ipv4HourlyRate;
    }

    public BaselinePolicy defaultBaseline() {
        return defaultBaseline;
    }

    /**
     * Hours not running inside the baseline between {@code dayStart} and {@code dayEnd}, by cause.
     * {@code transitions} are sorted ascending; {@code seed}/{@code seedCause} describe the state at
     * {@code dayStart} and what caused it.
     */
    public Map<SavingsCause, BigDecimal> avoidedByCause(Instant dayStart, Instant dayEnd, VmStatus seed,
                                                        SavingsCause seedCause, List<TransitionWithCause> transitions,
                                                        BaselinePolicy baseline) {
        Map<SavingsCause, Long> millis = new EnumMap<>(SavingsCause.class);
        VmStatus status = seed;
        SavingsCause cause = seedCause == null ? SavingsCause.OTHER : seedCause;
        Instant cursor = dayStart;
        for (TransitionWithCause t : transitions == null ? List.<TransitionWithCause>of() : transitions) {
            if (!t.at().isAfter(cursor) || !t.at().isBefore(dayEnd)) continue;
            credit(millis, status, cause, cursor, t.at(), baseline);
            status = t.newStatus();
            cause = t.cause() == null ? SavingsCause.OTHER : t.cause();
            cursor = t.at();
        }
        credit(millis, status, cause, cursor, dayEnd, baseline);

        Map<SavingsCause, BigDecimal> hours = new EnumMap<>(SavingsCause.class);
        millis.forEach((c, ms) -> {
            if (ms > 0) hours.put(c, BigDecimal.valueOf(ms).divide(MILLIS_PER_HOUR, 3, RoundingMode.HALF_UP));
        });
        return hours;
    }

    private static void credit(Map<SavingsCause, Long> millis, VmStatus status, SavingsCause cause,
                               Instant from, Instant to, BaselinePolicy baseline) {
        if (status != VmStatus.STOPPED && status != VmStatus.STOPPING) return;
        long inBaseline = baselineMillis(from, to, baseline);
        if (inBaseline > 0) millis.merge(cause, inBaseline, Long::sum);
    }

    /** Milliseconds of [from, to) that fall inside the baseline, day by day (UTC). */
    static long baselineMillis(Instant from, Instant to, BaselinePolicy baseline) {
        long total = 0;
        Instant dayStart = from.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        while (dayStart.isBefore(to)) {
            DayOfWeek dow = dayStart.atZone(ZoneOffset.UTC).getDayOfWeek();
            if (baseline.days().contains(dow) && baseline.hoursPerDay() > 0) {
                Instant windowEnd = dayStart.plus(Duration.ofHours(baseline.hoursPerDay()));
                Instant s = from.isAfter(dayStart) ? from : dayStart;
                Instant e = to.isBefore(windowEnd) ? to : windowEnd;
                if (e.isAfter(s)) total += Duration.between(s, e).toMillis();
            }
            dayStart = dayStart.plus(Duration.ofDays(1));
        }
        return total;
    }

    /**
     * gross = hours x rate; residual storage = storageGib x storageGbMonthRate / 30 x hours / 24;
     * residual IPv4 = hours x savings.ipv4-hourly-rate when the VM has a public IP; commitment
     * adjustment = gross x coverage%; net = gross - residuals - adjustment, never below zero.
     */
    public Money money(BigDecimal hours, BigDecimal hourlyRate, long storageGib, BigDecimal storageGbMonthRate,
                       boolean hasPublicIp, BigDecimal coveragePercent) {
        BigDecimal rate = hourlyRate == null ? BigDecimal.ZERO : hourlyRate;
        BigDecimal gross = hours.multiply(rate);
        BigDecimal storage = storageGbMonthRate == null ? BigDecimal.ZERO
                : storageGbMonthRate.multiply(BigDecimal.valueOf(storageGib))
                        .divide(DAYS_PER_MONTH, 8, RoundingMode.HALF_UP)
                        .multiply(hours).divide(HOURS_PER_DAY, 8, RoundingMode.HALF_UP);
        BigDecimal ipv4 = hasPublicIp && ipv4HourlyRate != null ? hours.multiply(ipv4HourlyRate) : BigDecimal.ZERO;
        BigDecimal coverage = coveragePercent == null ? BigDecimal.ZERO : coveragePercent.min(HUNDRED).max(BigDecimal.ZERO);
        BigDecimal adjustment = gross.multiply(coverage).divide(HUNDRED, 8, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(storage).subtract(ipv4).subtract(adjustment).max(BigDecimal.ZERO);
        String note = coverage.compareTo(HUNDRED) >= 0 ? FULLY_COVERED_NOTE : null;
        return new Money(scale(gross), scale(storage), scale(ipv4), scale(adjustment), scale(net), note);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
