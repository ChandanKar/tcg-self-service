package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.ReservationCoverageSnapshot;

import java.math.BigDecimal;
import java.sql.Date;

/**
 * One day's account-wide Reserved Instance / Savings Plan coverage &amp; utilization. Any field
 * may be null — e.g. {@code riUtilizationPercent} for an account with no RIs at all, or every
 * field before the first successful {@code ReservationCoverageScheduler} run.
 * {@code coveredCost} is reserved for a future refinement of {@link
 * com.tcgdigital.vmcontrol.service.ReservationCoverageService} — not populated yet.
 */
public record ReservationCoverageSnapshotDTO(
        Date snapshotDate,
        BigDecimal onDemandCost,
        BigDecimal coveredCost,
        BigDecimal coveragePercent,
        BigDecimal riUtilizationPercent,
        BigDecimal spCoveragePercent,
        BigDecimal spUtilizationPercent,
        BigDecimal netSavings
) {
    public static ReservationCoverageSnapshotDTO fromEntity(ReservationCoverageSnapshot s) {
        return new ReservationCoverageSnapshotDTO(
                s.getSnapshotDate(),
                s.getOnDemandCost(),
                s.getCoveredCost(),
                s.getCoveragePercent(),
                s.getRiUtilizationPercent(),
                s.getSpCoveragePercent(),
                s.getSpUtilizationPercent(),
                s.getNetSavings()
        );
    }
}
