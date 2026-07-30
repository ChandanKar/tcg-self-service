package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostReconciliationRowDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-environment estimated-vs-actual cost comparison, derived from the same daily snapshots
 * {@link CostEstimationService#getSpendTrend} reads — a separate small service rather than a
 * method on {@code CostEstimationService} since this is a daily-snapshot view, not a live-VM
 * bundle view (see that class's Javadoc on why every other view derives from one bundle build).
 */
@Service
public class CostReconciliationService {

    private final CostDailySnapshotRepository costDailySnapshotRepository;

    public CostReconciliationService(CostDailySnapshotRepository costDailySnapshotRepository) {
        this.costDailySnapshotRepository = costDailySnapshotRepository;
    }

    /**
     * @return one row per environment that has at least one snapshot in the window, sorted by
     *         estimated cost descending. {@code actualCost}/{@code variancePercent} stay null for
     *         an environment until {@code ActualCostIngestionScheduler} has ingested real data
     *         for it — never fabricated as zero.
     */
    public List<CostReconciliationRowDTO> getReconciliation(int days) {
        Date since = Date.valueOf(LocalDate.now().minusDays(days));
        List<CostDailySnapshot> snapshots = costDailySnapshotRepository.findWithEnvironmentSince(since);

        Map<String, Accumulator> byEnvironment = new LinkedHashMap<>();
        for (CostDailySnapshot snapshot : snapshots) {
            Environment env = snapshot.getEnvironment();
            Accumulator acc = byEnvironment.computeIfAbsent(env.getEnvironmentId(),
                    k -> new Accumulator(env.getDisplayName()));
            acc.estimatedTotal = acc.estimatedTotal.add(nullToZero(snapshot.getEstimatedCost()));
            if (snapshot.getActualCost() != null) {
                acc.actualTotal = acc.actualTotal.add(snapshot.getActualCost());
                acc.hasActual = true;
            }
        }

        List<CostReconciliationRowDTO> rows = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : byEnvironment.entrySet()) {
            Accumulator acc = entry.getValue();
            BigDecimal actualCost = acc.hasActual ? acc.actualTotal : null;
            BigDecimal variancePercent = null;
            if (actualCost != null && actualCost.compareTo(BigDecimal.ZERO) > 0) {
                variancePercent = acc.estimatedTotal.subtract(actualCost)
                        .divide(actualCost, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
            }
            rows.add(new CostReconciliationRowDTO(entry.getKey(), acc.displayName, acc.estimatedTotal, actualCost, variancePercent));
        }

        rows.sort(Comparator.comparing(CostReconciliationRowDTO::estimatedCost).reversed());
        return rows;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class Accumulator {
        private final String displayName;
        private BigDecimal estimatedTotal = BigDecimal.ZERO;
        private BigDecimal actualTotal = BigDecimal.ZERO;
        private boolean hasActual = false;

        private Accumulator(String displayName) {
            this.displayName = displayName;
        }
    }
}
