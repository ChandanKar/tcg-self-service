package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Scheduler-only writer for {@code cost_daily_snapshot} — read paths live in
 * {@link CostEstimationService}. Reuses {@link CostDataProvider} against historical day windows
 * so the trend chart isn't sparse on day one.
 */
@Service
public class CostSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(CostSnapshotService.class);

    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final CostDataProvider costDataProvider;
    private final CostDailySnapshotRepository costDailySnapshotRepository;
    private final CostDayBoundary dayBoundary;
    private final CostEstimationService costEstimationService;
    /** How many complete days each run (re)captures, so late state-history rows are picked up. */
    private final int recomputeDays;

    public CostSnapshotService(EnvironmentRepository environmentRepository,
                                VmRepository vmRepository,
                                CostDataProvider costDataProvider,
                                CostDailySnapshotRepository costDailySnapshotRepository,
                                CostDayBoundary dayBoundary,
                                @org.springframework.context.annotation.Lazy CostEstimationService costEstimationService,
                                @Value("${cost.snapshot.recompute-days:3}") int recomputeDays) {
        this.costEstimationService = costEstimationService;
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.costDataProvider = costDataProvider;
        this.costDailySnapshotRepository = costDailySnapshotRepository;
        this.dayBoundary = dayBoundary;
        this.recomputeDays = Math.max(1, recomputeDays);
    }

    /**
     * Captures the last {@code cost.snapshot.recompute-days} complete cost days, oldest first,
     * ending yesterday (E08-T01, H7): never today, which is still in progress and would clamp
     * compute to the hours elapsed so far while charging a full day of storage.
     */
    @Transactional
    public void captureDailySnapshot() {
        LocalDate today = dayBoundary.today();
        for (int i = recomputeDays; i >= 1; i--) {
            captureForDate(today.minusDays(i));
        }
    }

    /**
     * Captures {@code days} complete days of history ending yesterday, oldest first. Safe to
     * re-run: each date upserts its existing row rather than duplicating it.
     */
    @Transactional
    public void backfillHistoricalSnapshots(int days) {
        LocalDate today = dayBoundary.today();
        for (int i = days; i >= 1; i--) {
            captureForDate(today.minusDays(i));
        }
        log.info("Backfilled {} day(s) of cost snapshots ending {}", days, dayBoundary.yesterday());
        if (costEstimationService != null) {
            costEstimationService.invalidateBundles();
        }
    }

    private void captureForDate(LocalDate date) {
        List<Environment> environments = environmentRepository.findByIsActiveTrue();
        if (environments.isEmpty()) {
            return;
        }

        List<String> environmentIds = environments.stream().map(Environment::getEnvironmentId).toList();
        List<Vm> vms = vmRepository.findByEnvironmentIdIn(environmentIds);

        // Whole days in the cost zone (UTC by default), as Cost Explorer reports them.
        Timestamp windowStart = dayBoundary.startOf(date);
        Timestamp windowEnd = dayBoundary.startOf(date.plusDays(1));
        Date snapshotDate = Date.valueOf(date);

        Map<String, List<Vm>> vmsByEnvironmentId = vms.stream()
                .collect(Collectors.groupingBy(v -> v.getGroup().getEnvironment().getEnvironmentId()));

        Map<String, CostDataProvider.VmCostEstimate> estimates =
                costDataProvider.estimateCosts(vms, windowStart, windowEnd);

        for (Environment environment : environments) {
            List<Vm> envVms = vmsByEnvironmentId.getOrDefault(environment.getEnvironmentId(), List.of());
            BigDecimal total = envVms.stream()
                    .map(v -> estimates.get(v.getVmId()))
                    .filter(Objects::nonNull)
                    .map(CostDataProvider.VmCostEstimate::cost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            CostDailySnapshot snapshot = costDailySnapshotRepository
                    .findByEnvironmentEnvironmentIdAndSnapshotDate(environment.getEnvironmentId(), snapshotDate)
                    .orElseGet(CostDailySnapshot::new);
            snapshot.setEnvironment(environment);
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setEstimatedCost(total);
            costDailySnapshotRepository.save(snapshot);
        }
    }
}
