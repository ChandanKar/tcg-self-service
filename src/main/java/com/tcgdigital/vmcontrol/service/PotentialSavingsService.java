package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.EfficiencySnapshot;
import com.tcgdigital.vmcontrol.repository.EfficiencySnapshotRepository;
import com.tcgdigital.vmcontrol.service.CostEstimationService.VmOpportunity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Potential savings and Cost Efficiency % (E17-T05), as AWS defines it: 1 - potential savings /
 * optimisable spend. U23-T06: the potential is built from the parts Cost management shows, so the
 * Savings page's "Still on the table" adds up: per VM the LARGER of its idle cost (running and idle
 * now) and its downsize saving (an idle VM's stop and its rightsizing are not both counted), plus
 * the open waste findings while the waste checks are on. The assumed off-hours share of unscheduled
 * VMs (half their cost) is no longer counted: no page showed it, and it made up most of the figure.
 */
@Service
public class PotentialSavingsService {

    private static final Logger log = LoggerFactory.getLogger(PotentialSavingsService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Where a potential saving came from. */
    public enum PotentialCause { IDLE, RIGHTSIZING, WASTE }

    /**
     * @param byCause      the parts of {@code potentialSavings}; they add up to it
     * @param wasteChecked whether the waste checks are on, so WASTE is a measured part (0 when none is open)
     */
    public record Result(BigDecimal potentialSavings, BigDecimal optimisableSpend, BigDecimal efficiencyPercent,
                         Map<PotentialCause, BigDecimal> byCause, int vmCount, boolean wasteChecked) {
    }

    private final CostEstimationService costEstimationService;
    private final EfficiencySnapshotRepository snapshotRepository;
    private final CostDayBoundary dayBoundary;
    private final TeamResolver teamResolver;
    private final com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository;
    private com.tcgdigital.vmcontrol.repository.WasteFindingRepository wasteFindingRepository;
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public PotentialSavingsService(CostEstimationService costEstimationService,
                                   EfficiencySnapshotRepository snapshotRepository, CostDayBoundary dayBoundary,
                                   TeamResolver teamResolver,
                                   com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository) {
        this.costEstimationService = costEstimationService;
        this.snapshotRepository = snapshotRepository;
        this.dayBoundary = dayBoundary;
        this.teamResolver = teamResolver;
        this.environmentRepository = environmentRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWaste(com.tcgdigital.vmcontrol.repository.WasteFindingRepository wasteFindingRepository,
                  com.tcgdigital.vmcontrol.service.settings.SettingsService settings) {
        this.wasteFindingRepository = wasteFindingRepository;
        this.settings = settings;
    }

    /** Fleet-wide, or one environment when {@code environmentId} is not null. */
    @Transactional(readOnly = true)
    public Result compute(String environmentId) {
        List<VmOpportunity> opportunities = costEstimationService.listVmOpportunities().stream()
                .filter(o -> environmentId == null || environmentId.equals(o.environmentId()))
                .toList();
        Map<String, BigDecimal> waste = openWasteByEnvironment();
        if (waste == null) {
            return summarize(opportunities, null);
        }
        return summarize(opportunities, environmentId == null ? total(waste) : nz(waste.get(environmentId)));
    }

    /**
     * Open waste a month per environment id (findings without one under the key ""), or null while the
     * waste checks are off (waste.sweep.enabled, the switch the Waste page reads).
     */
    Map<String, BigDecimal> openWasteByEnvironment() {
        if (wasteFindingRepository == null || settings == null || !settings.getBoolean("waste.sweep.enabled", false)) {
            return null;
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Object[] row : wasteFindingRepository.openMonthlyByEnvironment()) {
            String env = row[0] == null ? "" : (String) row[0];
            result.merge(env, row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1], BigDecimal::add);
        }
        return result;
    }

    private static BigDecimal total(Map<String, BigDecimal> waste) {
        return waste.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param openWaste the open waste findings a month in scope, or null while the waste checks are off
     */
    static Result summarize(List<VmOpportunity> opportunities, BigDecimal openWaste) {
        BigDecimal potential = BigDecimal.ZERO;
        BigDecimal optimisable = BigDecimal.ZERO;
        Map<PotentialCause, BigDecimal> byCause = new EnumMap<>(PotentialCause.class);
        byCause.put(PotentialCause.IDLE, BigDecimal.ZERO);
        byCause.put(PotentialCause.RIGHTSIZING, BigDecimal.ZERO);
        int counted = 0;
        for (VmOpportunity o : opportunities) {
            if (!o.costKnown() || o.monthlyCost() == null) continue;
            counted++;
            optimisable = optimisable.add(o.monthlyCost());
            BigDecimal idle = o.idle() ? nz(o.monthlyIdleCost()) : BigDecimal.ZERO;
            BigDecimal rightsizing = nz(o.rightsizingSaving());
            PotentialCause cause = PotentialCause.IDLE;
            BigDecimal best = idle;
            if (rightsizing.compareTo(best) > 0) { best = rightsizing; cause = PotentialCause.RIGHTSIZING; }
            if (best.signum() > 0) {
                potential = potential.add(best);
                byCause.merge(cause, best, BigDecimal::add);
            }
        }
        if (openWaste != null) {
            // Waste is spend that could go too: on both sides of the ratio.
            potential = potential.add(openWaste);
            optimisable = optimisable.add(openWaste);
            byCause.put(PotentialCause.WASTE, openWaste);
        }
        BigDecimal efficiency = optimisable.signum() > 0
                ? BigDecimal.ONE.subtract(potential.divide(optimisable, 8, RoundingMode.HALF_UP))
                        .multiply(HUNDRED).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP)
                : null;
        Map<PotentialCause, BigDecimal> scaled = new EnumMap<>(PotentialCause.class);
        byCause.forEach((k, v) -> scaled.put(k, v.setScale(2, RoundingMode.HALF_UP)));
        return new Result(potential.setScale(2, RoundingMode.HALF_UP), optimisable.setScale(2, RoundingMode.HALF_UP),
                efficiency, scaled, counted, openWaste != null);
    }

    /** Write today's fleet row and one row per environment (after the ledger, E17-T05). */
    @Transactional
    public int captureSnapshot() {
        LocalDate today = dayBoundary.today();
        List<VmOpportunity> all = costEstimationService.listVmOpportunities();
        Map<String, BigDecimal> waste = openWasteByEnvironment();
        snapshotRepository.deleteBySnapshotDate(today);
        save(today, null, null, summarize(all, waste == null ? null : total(waste)));
        Map<String, List<VmOpportunity>> byEnvironment = new LinkedHashMap<>();
        all.forEach(o -> byEnvironment.computeIfAbsent(o.environmentId(), k -> new java.util.ArrayList<>()).add(o));
        byEnvironment.forEach((envId, list) -> save(today, envId,
                environmentRepository.findById(envId).map(teamResolver::resolveTeam).orElse(null),
                summarize(list, waste == null ? null : nz(waste.get(envId)))));
        log.info("Efficiency snapshot {}: fleet + {} environment(s)", today, byEnvironment.size());
        return 1 + byEnvironment.size();
    }

    private void save(LocalDate day, String environmentId, String team, Result r) {
        EfficiencySnapshot s = new EfficiencySnapshot();
        s.setSnapshotId(UUID.randomUUID().toString());
        s.setSnapshotDate(day);
        s.setEnvironmentId(environmentId);
        s.setTeam(team);
        s.setPotentialSavings(r.potentialSavings());
        s.setOptimisableSpend(r.optimisableSpend());
        s.setEfficiencyPercent(r.efficiencyPercent());
        snapshotRepository.save(s);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
