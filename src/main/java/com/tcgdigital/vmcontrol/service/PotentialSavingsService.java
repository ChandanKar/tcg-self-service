package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.EfficiencySnapshot;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.EfficiencySnapshotRepository;
import com.tcgdigital.vmcontrol.service.CostEstimationService.VmOpportunity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Potential savings and Cost Efficiency % (E17-T05), as AWS defines it: 1 - potential savings /
 * optimisable spend. Per VM the potential is the LARGEST of: its idle cost (if idle), its
 * downsize saving, and — for VMs no schedule or lease stops, outside production environments — an
 * assumed share of its cost spent off hours (savings.potential.off-hours-share, default 0.5).
 * Taking the largest is the de-duplication: an idle VM's stop and its rightsizing are not both
 * counted. VmType has no production value, so environments' is_production flag is the marker.
 */
@Service
public class PotentialSavingsService {

    private static final Logger log = LoggerFactory.getLogger(PotentialSavingsService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Where a VM's potential came from. */
    public enum PotentialCause { IDLE, RIGHTSIZING, OFF_HOURS }

    public record Result(BigDecimal potentialSavings, BigDecimal optimisableSpend, BigDecimal efficiencyPercent,
                         Map<PotentialCause, BigDecimal> byCause, int vmCount, BigDecimal offHoursShare) {
    }

    private final CostEstimationService costEstimationService;
    private final AutomationRuleRepository ruleRepository;
    private final EfficiencySnapshotRepository snapshotRepository;
    private final CostDayBoundary dayBoundary;
    private final TeamResolver teamResolver;
    private final com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository;
    private final BigDecimal offHoursShare;

    public PotentialSavingsService(CostEstimationService costEstimationService, AutomationRuleRepository ruleRepository,
                                   EfficiencySnapshotRepository snapshotRepository, CostDayBoundary dayBoundary,
                                   TeamResolver teamResolver,
                                   com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository,
                                   @Value("${savings.potential.off-hours-share:0.5}") BigDecimal offHoursShare) {
        this.costEstimationService = costEstimationService;
        this.ruleRepository = ruleRepository;
        this.snapshotRepository = snapshotRepository;
        this.dayBoundary = dayBoundary;
        this.teamResolver = teamResolver;
        this.environmentRepository = environmentRepository;
        this.offHoursShare = offHoursShare;
    }

    /** Fleet-wide, or one environment when {@code environmentId} is not null. */
    @Transactional(readOnly = true)
    public Result compute(String environmentId) {
        List<VmOpportunity> opportunities = costEstimationService.listVmOpportunities().stream()
                .filter(o -> environmentId == null || environmentId.equals(o.environmentId()))
                .toList();
        return summarize(opportunities, scheduledScopes(), offHoursShare);
    }

    /** Scope keys ("ENVIRONMENT:id", "GROUP:id", "VM:id") covered by an enabled calendar rule. */
    Set<String> scheduledScopes() {
        Set<String> keys = new HashSet<>();
        for (AutomationRule rule : ruleRepository.findAllFetchEnvironment()) {
            if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getTriggerType() != AutomationTriggerType.SCHEDULE) continue;
            AutomationScopeType scope = rule.getScopeType() == null ? AutomationScopeType.ENVIRONMENT : rule.getScopeType();
            String id = scope == AutomationScopeType.ENVIRONMENT ? rule.getEnvironment().getEnvironmentId() : rule.getScopeId();
            if (id != null) keys.add(scope.name() + ":" + id);
        }
        return keys;
    }

    static Result summarize(List<VmOpportunity> opportunities, Set<String> scheduledScopes, BigDecimal offHoursShare) {
        BigDecimal potential = BigDecimal.ZERO;
        BigDecimal optimisable = BigDecimal.ZERO;
        Map<PotentialCause, BigDecimal> byCause = new EnumMap<>(PotentialCause.class);
        int counted = 0;
        for (VmOpportunity o : opportunities) {
            if (!o.costKnown() || o.monthlyCost() == null) continue;
            counted++;
            optimisable = optimisable.add(o.monthlyCost());
            BigDecimal idle = o.idle() ? nz(o.monthlyIdleCost()) : BigDecimal.ZERO;
            BigDecimal rightsizing = nz(o.rightsizingSaving());
            boolean stoppedByPlatform = o.leaseEnabled()
                    || scheduledScopes.contains("ENVIRONMENT:" + o.environmentId())
                    || scheduledScopes.contains("GROUP:" + o.groupId())
                    || scheduledScopes.contains("VM:" + o.vmId());
            BigDecimal offHours = !stoppedByPlatform && !o.production()
                    ? o.monthlyCost().multiply(offHoursShare) : BigDecimal.ZERO;
            PotentialCause cause = PotentialCause.IDLE;
            BigDecimal best = idle;
            if (rightsizing.compareTo(best) > 0) { best = rightsizing; cause = PotentialCause.RIGHTSIZING; }
            if (offHours.compareTo(best) > 0) { best = offHours; cause = PotentialCause.OFF_HOURS; }
            if (best.signum() > 0) {
                potential = potential.add(best);
                byCause.merge(cause, best, BigDecimal::add);
            }
        }
        BigDecimal efficiency = optimisable.signum() > 0
                ? BigDecimal.ONE.subtract(potential.divide(optimisable, 8, RoundingMode.HALF_UP))
                        .multiply(HUNDRED).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP)
                : null;
        Map<PotentialCause, BigDecimal> scaled = new EnumMap<>(PotentialCause.class);
        byCause.forEach((k, v) -> scaled.put(k, v.setScale(2, RoundingMode.HALF_UP)));
        return new Result(potential.setScale(2, RoundingMode.HALF_UP), optimisable.setScale(2, RoundingMode.HALF_UP),
                efficiency, scaled, counted, offHoursShare);
    }

    /** Write today's fleet row and one row per environment (after the ledger, E17-T05). */
    @Transactional
    public int captureSnapshot() {
        LocalDate today = dayBoundary.today();
        List<VmOpportunity> all = costEstimationService.listVmOpportunities();
        Set<String> scheduled = scheduledScopes();
        snapshotRepository.deleteBySnapshotDate(today);
        save(today, null, null, summarize(all, scheduled, offHoursShare));
        Map<String, List<VmOpportunity>> byEnvironment = new LinkedHashMap<>();
        all.forEach(o -> byEnvironment.computeIfAbsent(o.environmentId(), k -> new java.util.ArrayList<>()).add(o));
        byEnvironment.forEach((envId, list) -> save(today, envId,
                environmentRepository.findById(envId).map(e -> teamResolver.resolveTeam(e.getMetadata())).orElse(null),
                summarize(list, scheduled, offHoursShare)));
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
