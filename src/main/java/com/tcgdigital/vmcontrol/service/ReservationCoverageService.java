package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ReservationCoverageSnapshot;
import com.tcgdigital.vmcontrol.repository.ReservationCoverageSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetReservationCoverageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetReservationCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.GetReservationUtilizationRequest;
import software.amazon.awssdk.services.costexplorer.model.GetReservationUtilizationResponse;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansCoverageResponse;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansUtilizationRequest;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansUtilizationResponse;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Account-wide Reserved Instance / Savings Plan coverage &amp; utilization, via the same Cost
 * Explorer APIs as {@link CostExplorerBillingService} (fixed to us-east-1). Each of the four
 * underlying calls is captured independently with its own try/catch — a parsing mistake or
 * missing IAM permission on one metric must not blank out the other three, and a field this
 * account has no data for (e.g. no Savings Plans purchased) stays null rather than a fabricated
 * zero, same discipline as the rest of Cost Management.
 */
@Service
public class ReservationCoverageService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCoverageService.class);

    @Value("${cost.reservations.enabled:true}")
    private boolean enabled;

    private final ReservationCoverageSnapshotRepository repository;
    private final CostExplorerClientProvider clientProvider;
    private final CostDayBoundary dayBoundary;

    public ReservationCoverageService(ReservationCoverageSnapshotRepository repository,
                                      CostExplorerClientProvider clientProvider,
                                      CostDayBoundary dayBoundary) {
        this.repository = repository;
        this.clientProvider = clientProvider;
        this.dayBoundary = dayBoundary;
    }

    public boolean isAvailable() {
        return enabled && clientProvider.isConfigured();
    }

    /**
     * Captures (or updates) today's reservation/savings-plan coverage snapshot. Safe to call
     * repeatedly in a day — upserts the same row rather than creating duplicates.
     */
    public void captureDailySnapshot() {
        if (!isAvailable()) {
            log.debug("Reservation coverage capture disabled or AWS unavailable — skipping");
            return;
        }

        // The row is dated the day it describes: yesterday, the last complete cost day (E08-T08).
        LocalDate coveredDay = dayBoundary.yesterday();
        Date snapshotDate = Date.valueOf(coveredDay);
        ReservationCoverageSnapshot snapshot = repository.findBySnapshotDate(snapshotDate)
                .orElseGet(() -> {
                    ReservationCoverageSnapshot s = new ReservationCoverageSnapshot();
                    s.setSnapshotDate(snapshotDate);
                    return s;
                });

        DateInterval period = DateInterval.builder()
                .start(coveredDay.toString())
                .end(coveredDay.plusDays(1).toString())
                .build();

        captureReservationCoverage(snapshot, period);
        captureReservationUtilization(snapshot, period);
        captureSavingsPlansCoverage(snapshot, period);
        captureSavingsPlansUtilization(snapshot, period);

        repository.save(snapshot);
        log.info("Reservation coverage snapshot captured for {}", coveredDay);
    }

    // ---- individual metric captures, each independently fault-tolerant ----

    private void captureReservationCoverage(ReservationCoverageSnapshot snapshot, DateInterval period) {
        try {
            GetReservationCoverageResponse response = clientProvider.client().getReservationCoverage(
                    GetReservationCoverageRequest.builder().timePeriod(period).build());
            if (response.total() == null) {
                return;
            }
            if (response.total().coverageHours() != null) {
                parseBigDecimal(response.total().coverageHours().coverageHoursPercentage())
                        .ifPresent(snapshot::setCoveragePercent);
            }
            if (response.total().coverageCost() != null) {
                parseBigDecimal(response.total().coverageCost().onDemandCost())
                        .ifPresent(snapshot::setOnDemandCost);
            }
        } catch (Exception e) {
            log.warn("Could not fetch reservation coverage (may be missing ce:GetReservationCoverage): {}", e.getMessage());
        }
    }

    private void captureReservationUtilization(ReservationCoverageSnapshot snapshot, DateInterval period) {
        try {
            GetReservationUtilizationResponse response = clientProvider.client().getReservationUtilization(
                    GetReservationUtilizationRequest.builder().timePeriod(period).build());
            if (response.total() == null) {
                return;
            }
            parseBigDecimal(response.total().utilizationPercentage()).ifPresent(snapshot::setRiUtilizationPercent);
            parseBigDecimal(response.total().netRISavings()).ifPresent(snapshot::setNetSavings);
        } catch (Exception e) {
            log.warn("Could not fetch reservation utilization (may be missing ce:GetReservationUtilization): {}", e.getMessage());
        }
    }

    private void captureSavingsPlansCoverage(ReservationCoverageSnapshot snapshot, DateInterval period) {
        try {
            GetSavingsPlansCoverageResponse response = clientProvider.client().getSavingsPlansCoverage(
                    GetSavingsPlansCoverageRequest.builder().timePeriod(period).build());
            if (response.savingsPlansCoverages() == null || response.savingsPlansCoverages().isEmpty()) {
                return;
            }
            var coverage = response.savingsPlansCoverages().get(0).coverage();
            if (coverage != null) {
                parseBigDecimal(coverage.coveragePercentage()).ifPresent(snapshot::setSpCoveragePercent);
                parseBigDecimal(coverage.spendCoveredBySavingsPlans()).ifPresent(snapshot::setCoveredCost);
            }
        } catch (Exception e) {
            log.warn("Could not fetch Savings Plans coverage (may be missing ce:GetSavingsPlansCoverage): {}", e.getMessage());
        }
    }

    private void captureSavingsPlansUtilization(ReservationCoverageSnapshot snapshot, DateInterval period) {
        try {
            GetSavingsPlansUtilizationResponse response = clientProvider.client().getSavingsPlansUtilization(
                    GetSavingsPlansUtilizationRequest.builder().timePeriod(period).build());
            if (response.total() == null || response.total().utilization() == null) {
                return;
            }
            parseBigDecimal(response.total().utilization().utilizationPercentage())
                    .ifPresent(snapshot::setSpUtilizationPercent);
        } catch (Exception e) {
            log.warn("Could not fetch Savings Plans utilization (may be missing ce:GetSavingsPlansUtilization): {}", e.getMessage());
        }
    }

    private Optional<BigDecimal> parseBigDecimal(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
