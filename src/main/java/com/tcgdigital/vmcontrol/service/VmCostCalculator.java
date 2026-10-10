package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Pure, stateless cost-estimation math — no DB access. Seed-state resolution (what a VM's
 * status was immediately before the window) is the caller's job, since it requires batched
 * DB lookups this class must stay free of to remain trivially unit-testable.
 */
@Component
public class VmCostCalculator {

    private static final BigDecimal MILLIS_PER_HOUR = BigDecimal.valueOf(3_600_000L);
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);

    /**
     * Sums RUNNING intervals across {@code windowStart}..{@code windowEnd}, clamped to the VM's
     * creation time and to "now" (never projects runtime into the future). {@code transitions}
     * must be sorted ascending by changedAt and already scoped to this VM within the window.
     * {@code seedStatus} is the status the VM was in at the (possibly clamped) window start.
     */
    public BigDecimal computeRuntimeHours(Vm vm, VmStatus seedStatus, List<VmStateHistory> transitions,
                                           Timestamp windowStart, Timestamp windowEnd) {
        Timestamp effectiveEnd = clampToNow(windowEnd);
        Timestamp effectiveStart = clampToCreation(windowStart, vm.getCreatedAt());
        if (!effectiveStart.before(effectiveEnd)) {
            return BigDecimal.ZERO;
        }

        long runningMillis = 0L;
        VmStatus currentStatus = seedStatus;
        Timestamp cursor = effectiveStart;

        if (transitions != null) {
            for (VmStateHistory transition : transitions) {
                Timestamp changedAt = transition.getChangedAt();
                if (!changedAt.after(cursor) || !changedAt.before(effectiveEnd)) {
                    continue;
                }
                if (currentStatus == VmStatus.RUNNING) {
                    runningMillis += changedAt.getTime() - cursor.getTime();
                }
                currentStatus = transition.getNewStatus();
                cursor = changedAt;
            }
        }

        if (currentStatus == VmStatus.RUNNING) {
            runningMillis += effectiveEnd.getTime() - cursor.getTime();
        }

        return BigDecimal.valueOf(runningMillis)
                .divide(MILLIS_PER_HOUR, 3, RoundingMode.HALF_UP);
    }

    /**
     * {@code hourlyRate x runtimeHours} (compute) plus a pro-rated slice of
     * {@code storageGib x storageGbMonthRate} for {@code windowDays} out of a 30-day month
     * (storage bills regardless of running state). A 30-day {@code windowDays} yields the full
     * monthly storage rate (for "current monthly cost" views); a 1-day window yields ~1/30th of
     * it (for daily snapshots) — same formula, no separate proration step at call sites.
     */
    public BigDecimal estimateCost(BigDecimal hourlyRate, BigDecimal runtimeHours,
                                    long storageGib, BigDecimal storageGbMonthRate, BigDecimal windowDays) {
        BigDecimal computeCost = hourlyRate.multiply(runtimeHours);
        BigDecimal proratedStorageRate = storageGbMonthRate
                .multiply(windowDays)
                .divide(DAYS_PER_MONTH, 6, RoundingMode.HALF_UP);
        BigDecimal storageCost = proratedStorageRate.multiply(BigDecimal.valueOf(storageGib));
        return computeCost.add(storageCost).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Fractional days between two timestamps, for {@link #estimateCost} proration.
     */
    public BigDecimal windowDays(Timestamp windowStart, Timestamp windowEnd) {
        long minutes = java.time.Duration.between(windowStart.toInstant(), windowEnd.toInstant()).toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(1440), 6, RoundingMode.HALF_UP);
    }

    /**
     * Days of storage to charge a VM for in a window (E08-T01): the window clamped to the VM's
     * creation and to now, so storage is never billed before the VM existed or into the future.
     */
    public BigDecimal storageWindowDays(Timestamp vmCreatedAt, Timestamp windowStart, Timestamp windowEnd) {
        Timestamp start = clampToCreation(windowStart, vmCreatedAt);
        Timestamp end = clampToNow(windowEnd);
        if (!start.before(end)) {
            return BigDecimal.ZERO;
        }
        return windowDays(start, end);
    }

    private Timestamp clampToNow(Timestamp windowEnd) {
        Timestamp now = Timestamp.from(Instant.now());
        return windowEnd.after(now) ? now : windowEnd;
    }

    private Timestamp clampToCreation(Timestamp windowStart, Timestamp vmCreatedAt) {
        if (vmCreatedAt != null && vmCreatedAt.after(windowStart)) {
            return vmCreatedAt;
        }
        return windowStart;
    }
}
