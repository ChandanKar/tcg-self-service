package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationReportType;
import com.tcgdigital.vmcontrol.model.WeeklyOptimizationSnapshot;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.WeeklyOptimizationSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures and reads back last week's per-environment totals for the idle-waste and rightsizing
 * weekly reports — the historical baseline neither has otherwise, unlike cost which already has
 * {@link com.tcgdigital.vmcontrol.model.CostDailySnapshot} to diff against.
 */
@Service
public class WeeklySnapshotService {

    private final WeeklyOptimizationSnapshotRepository snapshotRepository;
    private final EnvironmentRepository environmentRepository;

    public WeeklySnapshotService(WeeklyOptimizationSnapshotRepository snapshotRepository,
                                  EnvironmentRepository environmentRepository) {
        this.snapshotRepository = snapshotRepository;
        this.environmentRepository = environmentRepository;
    }

    /**
     * Upserts this week's total for every environment in {@code totalByEnvironmentId} — safe to
     * call more than once for the same {@code snapshotDate} (e.g. a retried scheduler run), since
     * an existing row for that environment/type/date is updated in place rather than duplicated.
     */
    @Transactional
    public void captureSnapshot(WeeklyOptimizationReportType type, Date snapshotDate,
                                Map<String, BigDecimal> totalByEnvironmentId) {
        for (Map.Entry<String, BigDecimal> entry : totalByEnvironmentId.entrySet()) {
            String environmentId = entry.getKey();
            WeeklyOptimizationSnapshot snapshot = snapshotRepository
                    .findByEnvironment_EnvironmentIdAndReportTypeAndSnapshotDate(environmentId, type, snapshotDate)
                    .orElseGet(WeeklyOptimizationSnapshot::new);

            if (snapshot.getEnvironment() == null) {
                Environment environment = environmentRepository.findById(environmentId).orElse(null);
                if (environment == null) {
                    continue;
                }
                snapshot.setEnvironment(environment);
                snapshot.setReportType(type);
                snapshot.setSnapshotDate(snapshotDate);
            }
            snapshot.setMetricValue(entry.getValue());
            snapshotRepository.save(snapshot);
        }
    }

    /**
     * @return per-environment totals from the given week, or an empty map if this report type
     *         has never captured a snapshot for that week (e.g. the very first run) — callers
     *         must treat a missing entry as "no prior data," never as zero.
     */
    public Map<String, BigDecimal> getPreviousWeekTotals(WeeklyOptimizationReportType type, Date snapshotDate) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (WeeklyOptimizationSnapshot snapshot : snapshotRepository.findByReportTypeAndSnapshotDate(type, snapshotDate)) {
            totals.put(snapshot.getEnvironment().getEnvironmentId(), snapshot.getMetricValue());
        }
        return totals;
    }
}
