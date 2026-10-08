package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An environment's cost for the people who use it (E18-T01, G7). Estimated figures come from
 * daily snapshots plus today's live estimate; actual figures from billing where present.
 *
 * <p>scope FULL: the whole environment. scope GROUPS: a group-only grant; totals and top VMs
 * cover the visible groups only, and the environment-level actuals, trend and forecast are null.
 */
public record EnvironmentCostDTO(
        String environmentId,
        String scope,
        LocalDate monthStart,
        BigDecimal monthToDateEstimated,
        BigDecimal monthToDateActual,
        int actualDays,
        BigDecimal previousMonthSameDays,
        BigDecimal changePercent,
        BigDecimal forecastMonthEnd,
        String forecastBasis,
        List<DailyCost> daily,
        List<TopVm> topVms,
        BigDecimal savingsMtd,
        String savingsSource,
        ScheduleStatus scheduleStatus,
        LeaseStatus leaseStatus
) {
    public record DailyCost(LocalDate date, BigDecimal estimated, BigDecimal actual) {}

    public record TopVm(String vmId, String name, BigDecimal mtdCost, String status, boolean costKnown) {}

    public record ScheduleStatus(int ruleCount, Instant nextStop, Instant nextStart) {}

    /** Reserved for environment leases (E15); null until leases exist. */
    public record LeaseStatus(Instant endsAt) {}
}
