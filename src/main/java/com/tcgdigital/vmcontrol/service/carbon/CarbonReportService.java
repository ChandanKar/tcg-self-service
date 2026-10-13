package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.dto.CarbonFleetDTO;
import com.tcgdigital.vmcontrol.dto.EnvironmentCarbonDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.EnvironmentCarbonMonthly;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentCarbonMonthlyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Carbon next to cost (E37-T03): an environment's last 12 months and the fleet by environment for a
 * month, from the attributed rows (E37-T02). Market-based kg CO2e is the headline, location-based is
 * carried alongside.
 */
@Service
public class CarbonReportService {

    public static final String COVERAGE_NOTE = "Estimated by cost share from AWS Customer Carbon Footprint data, "
            + "which AWS publishes monthly with a delay of several weeks.";

    private final EnvironmentCarbonMonthlyRepository attributedRepository;
    private final CostDailySnapshotRepository snapshotRepository;
    private Clock clock = Clock.systemUTC();

    public CarbonReportService(EnvironmentCarbonMonthlyRepository attributedRepository, CostDailySnapshotRepository snapshotRepository) {
        this.attributedRepository = attributedRepository;
        this.snapshotRepository = snapshotRepository;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private YearMonth thisMonth() {
        return YearMonth.now(clock);
    }

    @Transactional(readOnly = true)
    public EnvironmentCarbonDTO environment(String environmentId) {
        YearMonth last = thisMonth().minusMonths(1);
        YearMonth first = last.minusMonths(11);
        Map<String, EnvironmentCarbonMonthly> rows = new LinkedHashMap<>();
        attributedRepository.findByEnvironmentEnvironmentIdAndMonthBetweenOrderByMonth(environmentId, first.atDay(1), last.atDay(1))
                .forEach(r -> rows.put(YearMonth.from(r.getMonth()).toString(), r));
        Map<String, BigDecimal> costs = monthlyCost(environmentId, first, last);
        List<EnvironmentCarbonDTO.Month> months = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            EnvironmentCarbonMonthly r = rows.get(m.toString());
            BigDecimal cost = costs.get(m.toString());
            BigDecimal kg = r == null ? null : headline(r.getEmissionsMbmKg(), r.getEmissionsLbmKg());
            months.add(new EnvironmentCarbonDTO.Month(m.toString(), r == null ? null : r.getEmissionsMbmKg(),
                    r == null ? null : r.getEmissionsLbmKg(), cost, perDollar(kg, cost)));
        }
        EnvironmentCarbonDTO.Month latest = null;
        for (EnvironmentCarbonDTO.Month m : months) {
            if (m.mbmKg() != null || m.lbmKg() != null) latest = m;
        }
        return new EnvironmentCarbonDTO(environmentId, months, latest == null ? null : latest.month(),
                latest == null ? null : latest.mbmKg(), latest == null ? null : latest.lbmKg(), COVERAGE_NOTE);
    }

    /** One month by environment; {@code month} null means the newest month with data. */
    @Transactional(readOnly = true)
    public CarbonFleetDTO fleet(YearMonth month) {
        YearMonth last = thisMonth().minusMonths(1);
        YearMonth first = last.minusMonths(11);
        List<EnvironmentCarbonMonthly> all = attributedRepository.findByMonthBetween(first.atDay(1), last.atDay(1));
        TreeSet<String> available = new TreeSet<>(Comparator.reverseOrder());
        all.forEach(r -> available.add(YearMonth.from(r.getMonth()).toString()));
        String chosen = month != null ? month.toString() : available.isEmpty() ? null : available.first();

        List<CarbonFleetDTO.Row> rows = new ArrayList<>();
        BigDecimal mbm = null, lbm = null, coverage = null;
        List<EnvironmentCarbonMonthly> monthRows = chosen == null ? List.of()
                : month != null && (month.isBefore(first) || month.isAfter(last))
                        ? attributedRepository.findByMonthBetween(month.atDay(1), month.atDay(1))
                        : all.stream().filter(r -> YearMonth.from(r.getMonth()).toString().equals(chosen)).toList();
        for (EnvironmentCarbonMonthly r : monthRows) {
            boolean unallocated = r.getEnvironment() == null;
            BigDecimal kg = headline(r.getEmissionsMbmKg(), r.getEmissionsLbmKg());
            rows.add(new CarbonFleetDTO.Row(unallocated ? null : r.getEnvironment().getEnvironmentId(),
                    unallocated ? "Unallocated" : name(r), r.getEmissionsMbmKg(), r.getEmissionsLbmKg(), r.getCostBasis(),
                    unallocated ? null : perDollar(kg, r.getCostBasis()), unallocated));
            mbm = add(mbm, r.getEmissionsMbmKg());
            lbm = add(lbm, r.getEmissionsLbmKg());
            if (r.getCoveragePct() != null) coverage = r.getCoveragePct();
        }
        rows.sort(Comparator.comparing((CarbonFleetDTO.Row r) -> r.unallocated())
                .thenComparing(r -> headline(r.mbmKg(), r.lbmKg()) == null ? BigDecimal.ZERO : headline(r.mbmKg(), r.lbmKg()),
                        Comparator.reverseOrder()));

        Map<String, BigDecimal[]> trend = new TreeMap<>();
        for (EnvironmentCarbonMonthly r : all) {
            BigDecimal[] t = trend.computeIfAbsent(YearMonth.from(r.getMonth()).toString(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal kg = headline(r.getEmissionsMbmKg(), r.getEmissionsLbmKg());
            if (kg == null) continue;
            if (r.getEnvironment() == null) t[1] = t[1].add(kg); else t[0] = t[0].add(kg);
        }
        List<CarbonFleetDTO.TrendPoint> points = new ArrayList<>();
        trend.forEach((m, t) -> points.add(new CarbonFleetDTO.TrendPoint(m, scale(t[0]), scale(t[1]))));
        return new CarbonFleetDTO(chosen, new ArrayList<>(available), rows, mbm, lbm, coverage, points);
    }

    private static String name(EnvironmentCarbonMonthly r) {
        String display = r.getEnvironment().getDisplayName();
        return display == null || display.isBlank() ? r.getEnvironment().getName() : display;
    }

    /** Each month's cost: per day the actual where there is one, else the estimate. */
    private Map<String, BigDecimal> monthlyCost(String environmentId, YearMonth first, YearMonth last) {
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        for (CostDailySnapshot s : snapshotRepository.findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                environmentId, Date.valueOf(first.atDay(1)), Date.valueOf(last.atEndOfMonth()))) {
            BigDecimal day = s.getActualCost() != null ? s.getActualCost() : s.getEstimatedCost();
            if (day == null) continue;
            LocalDate date = s.getSnapshotDate().toLocalDate();
            costs.merge(YearMonth.from(date).toString(), day, BigDecimal::add);
        }
        costs.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));
        return costs;
    }

    static BigDecimal headline(BigDecimal mbm, BigDecimal lbm) {
        return mbm != null ? mbm : lbm;
    }

    static BigDecimal perDollar(BigDecimal kg, BigDecimal cost) {
        if (kg == null || cost == null || cost.signum() <= 0) return null;
        return kg.divide(cost, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return b == null ? a : a == null ? b : a.add(b);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
