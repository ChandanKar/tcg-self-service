package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationTriggerSource;
import com.tcgdigital.vmcontrol.model.ReservationCoverageSnapshot;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.ReservationCoverageSnapshotRepository;
import com.tcgdigital.vmcontrol.repository.SavingsLedgerEntryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the savings ledger (E17-T03): for each day, every active EC2 VM's hours not running inside
 * its baseline, by cause, priced at On-Demand rates net of residual EBS, public IPv4 and
 * commitment coverage. A day is rewritten as a whole, so re-running it (late history) is safe.
 * EKS node groups (scaling, not stopping) and VMs without a known price are left out.
 */
@Service
public class SavingsLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SavingsLedgerService.class);
    public static final int MAX_BACKFILL_DAYS = 90;

    private final VmRepository vmRepository;
    private final VmStateHistoryRepository historyRepository;
    private final OperationExecutionRepository executionRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final VmVolumeSnapshotRepository volumeRepository;
    private final ReservationCoverageSnapshotRepository coverageRepository;
    private final SavingsLedgerEntryRepository ledgerRepository;
    private final PricingReferenceService pricing;
    private final SavingsCalculator calculator;
    private final CostDayBoundary dayBoundary;
    private final TeamResolver teamResolver;
    private final int recomputeDays;

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.repository.VmResizeEventRepository resizeEventRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private VmCostCalculator costCalculator;

    @Value("${savings.rightsizing.credit-days:365}")
    private int rightsizingCreditDays = 365;

    public SavingsLedgerService(VmRepository vmRepository, VmStateHistoryRepository historyRepository,
                                OperationExecutionRepository executionRepository,
                                VmInventorySnapshotRepository inventoryRepository,
                                VmVolumeSnapshotRepository volumeRepository,
                                ReservationCoverageSnapshotRepository coverageRepository,
                                SavingsLedgerEntryRepository ledgerRepository, PricingReferenceService pricing,
                                SavingsCalculator calculator, CostDayBoundary dayBoundary, TeamResolver teamResolver,
                                @Value("${savings.ledger.recompute-days:3}") int recomputeDays) {
        this.vmRepository = vmRepository;
        this.historyRepository = historyRepository;
        this.executionRepository = executionRepository;
        this.inventoryRepository = inventoryRepository;
        this.volumeRepository = volumeRepository;
        this.coverageRepository = coverageRepository;
        this.ledgerRepository = ledgerRepository;
        this.pricing = pricing;
        this.calculator = calculator;
        this.dayBoundary = dayBoundary;
        this.teamResolver = teamResolver;
        this.recomputeDays = Math.max(1, recomputeDays);
    }

    /** The scheduled run: the last savings.ledger.recompute-days complete days, oldest first. */
    @Transactional
    public void captureTrailing() {
        captureTrailing(recomputeDays);
    }

    @Transactional
    public void captureTrailing(int days) {
        LocalDate today = dayBoundary.today();
        for (int i = days; i >= 1; i--) {
            captureForDate(today.minusDays(i));
        }
    }

    /** Rebuild {@code days} (1..90) complete days ending yesterday. */
    @Transactional
    public int backfill(int days) {
        int bounded = Math.max(1, Math.min(MAX_BACKFILL_DAYS, days));
        captureTrailing(bounded);
        return bounded;
    }

    /** @return how many ledger rows the day now has */
    @Transactional
    public int captureForDate(LocalDate date) {
        Timestamp windowStart = dayBoundary.startOf(date);
        Timestamp windowEnd = dayBoundary.startOf(date.plusDays(1));

        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(vm -> vm.getProvider() != CloudProvider.AWS_EKS)
                .toList();
        ledgerRepository.deleteByLedgerDate(date);
        if (vms.isEmpty()) return 0;
        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();

        Map<String, List<VmStateHistory>> transitions = new LinkedHashMap<>();
        for (VmStateHistory h : historyRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(
                vmIds, windowStart, windowEnd)) {
            transitions.computeIfAbsent(h.getVm().getVmId(), k -> new ArrayList<>()).add(h);
        }
        Map<String, VmStateHistory> before = new HashMap<>();
        for (VmStateHistory h : historyRepository.findLatestBeforeForVms(vmIds, windowStart)) {
            before.put(h.getVm().getVmId(), h); // on a tie, the last row wins
        }
        Set<String> operationIds = new HashSet<>();
        transitions.values().forEach(list -> list.forEach(h -> addIfPresent(operationIds, h.getOperationId())));
        before.values().forEach(h -> addIfPresent(operationIds, h.getOperationId()));
        Map<String, OperationTriggerSource> sources = new HashMap<>();
        if (!operationIds.isEmpty()) {
            for (Object[] row : executionRepository.findTriggerSourcesByIds(operationIds)) {
                sources.put((String) row[0], (OperationTriggerSource) row[1]);
            }
        }
        Map<String, String> instanceTypes = new HashMap<>();
        inventoryRepository.findInstanceTypesByVmIds(vmIds).forEach(p -> instanceTypes.put(p.getVmId(), p.getInstanceType()));
        Set<String> withPublicIp = new HashSet<>(inventoryRepository.findVmIdsWithPublicIp(vmIds));
        Map<String, Long> storage = new HashMap<>();
        volumeRepository.sumSizeGibByVmIds(vmIds).forEach(t -> storage.put(t.getVmId(), t.getTotalSizeGib()));
        BigDecimal coverage = coverageRepository.findBySnapshotDate(Date.valueOf(date))
                .map(SavingsLedgerService::coverageOf).orElse(null);
        BigDecimal storageRate = pricing.getStorageGbMonthRate();
        // Latest resize per VM within the credit window (E17-T04).
        Map<String, com.tcgdigital.vmcontrol.model.VmResizeEvent> resizes = new HashMap<>();
        Timestamp creditSince = Timestamp.from(windowStart.toInstant().minus(java.time.Duration.ofDays(rightsizingCreditDays)));
        resizeEventRepository.findByVmIdInAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAsc(
                vmIds, creditSince, windowEnd).forEach(e -> resizes.put(e.getVmId(), e));

        int rows = 0;
        int unpriced = 0;
        for (Vm vm : vms) {
            PricingReferenceService.PriceLookupResult rate =
                    pricing.lookupHourlyRate(vm.getProvider().name(), instanceTypes.get(vm.getVmId()), vm.getRegion());
            if (!rate.priceKnown()) {
                unpriced++;
                continue;
            }
            List<VmStateHistory> dayTransitions = transitions.getOrDefault(vm.getVmId(), List.of());
            VmStateHistory seedRow = before.get(vm.getVmId());
            VmStatus seed = !dayTransitions.isEmpty() && dayTransitions.get(0).getPreviousStatus() != null
                    ? dayTransitions.get(0).getPreviousStatus()
                    : seedRow != null ? seedRow.getNewStatus() : vm.getStatus();
            SavingsCause seedCause = seedRow != null ? causeOf(seedRow, sources) : SavingsCause.OTHER;
            List<SavingsCalculator.TransitionWithCause> withCause = dayTransitions.stream()
                    .map(h -> new SavingsCalculator.TransitionWithCause(h.getChangedAt().toInstant(), h.getNewStatus(),
                            causeOf(h, sources)))
                    .toList();
            // Never credit hours before the VM existed.
            java.time.Instant vmStart = vm.getCreatedAt() != null && vm.getCreatedAt().after(windowStart)
                    ? vm.getCreatedAt().toInstant() : windowStart.toInstant();
            if (!vmStart.isBefore(windowEnd.toInstant())) continue;
            Map<SavingsCause, BigDecimal> hours = calculator.avoidedByCause(vmStart, windowEnd.toInstant(),
                    seed, seedCause, withCause, calculator.defaultBaseline());
            Environment env = vm.getGroup().getEnvironment();
            String team = teamResolver.resolveTeam(env.getMetadata());
            for (Map.Entry<SavingsCause, BigDecimal> e : hours.entrySet()) {
                SavingsCalculator.Money money = calculator.money(e.getValue(), rate.hourlyRate(),
                        storage.getOrDefault(vm.getVmId(), 0L), storageRate, withPublicIp.contains(vm.getVmId()), coverage);
                SavingsLedgerEntry entry = new SavingsLedgerEntry();
                entry.setEntryId(UUID.randomUUID().toString());
                entry.setLedgerDate(date);
                entry.setVmId(vm.getVmId());
                entry.setEnvironmentId(env.getEnvironmentId());
                entry.setTeam(team);
                entry.setCause(e.getKey());
                entry.setAvoidedHours(e.getValue());
                entry.setHourlyRate(rate.hourlyRate());
                entry.setGrossSaving(money.gross());
                entry.setResidualStorageCost(money.residualStorage());
                entry.setResidualIpv4Cost(money.residualIpv4());
                entry.setCommitmentCoveragePercent(coverage);
                entry.setCommitmentAdjustment(money.commitmentAdjustment());
                entry.setNetSaving(money.net());
                entry.setPriceApproximate(rate.approximate());
                entry.setNote(money.note());
                ledgerRepository.save(entry);
                rows++;
            }
            if (writeRightsizing(vm, date, env, team, seed, dayTransitions, windowStart, windowEnd,
                    resizes.get(vm.getVmId()), coverage)) {
                rows++;
            }
        }
        log.info("Savings ledger {}: {} row(s) for {} VM(s); {} without a known price skipped", date, rows, vms.size(), unpriced);
        return rows;
    }

    /**
     * Rightsizing (E17-T04): after a downsize, every hour the VM ran saves the rate difference. No
     * residual EBS/IPv4 (the VM was running); commitment coverage applies as for stops. Upsizes and
     * unknown prices write nothing.
     */
    private boolean writeRightsizing(Vm vm, LocalDate date, Environment env, String team, VmStatus seed,
                                     List<VmStateHistory> dayTransitions, Timestamp windowStart, Timestamp windowEnd,
                                     com.tcgdigital.vmcontrol.model.VmResizeEvent resize, BigDecimal coverage) {
        if (resize == null) return false;
        String provider = vm.getProvider().name();
        PricingReferenceService.PriceLookupResult oldRate =
                pricing.lookupHourlyRate(provider, resize.getOldInstanceType(), vm.getRegion());
        PricingReferenceService.PriceLookupResult newRate =
                pricing.lookupHourlyRate(provider, resize.getNewInstanceType(), vm.getRegion());
        if (!oldRate.priceKnown() || !newRate.priceKnown()
                || oldRate.hourlyRate().compareTo(newRate.hourlyRate()) <= 0) {
            return false;
        }
        BigDecimal delta = oldRate.hourlyRate().subtract(newRate.hourlyRate());
        // Only hours after the resize count on the day it happened.
        Timestamp from = resize.getChangedAt().after(windowStart) ? resize.getChangedAt() : windowStart;
        BigDecimal hours = costCalculator.computeRuntimeHours(vm, seed, dayTransitions, from, windowEnd);
        if (hours.signum() <= 0) return false;
        SavingsCalculator.Money money = calculator.money(hours, delta, 0, null, false, coverage);
        SavingsLedgerEntry entry = new SavingsLedgerEntry();
        entry.setEntryId(UUID.randomUUID().toString());
        entry.setLedgerDate(date);
        entry.setVmId(vm.getVmId());
        entry.setEnvironmentId(env.getEnvironmentId());
        entry.setTeam(team);
        entry.setCause(SavingsCause.RIGHTSIZING);
        entry.setAvoidedHours(hours);
        entry.setHourlyRate(delta);
        entry.setGrossSaving(money.gross());
        entry.setCommitmentCoveragePercent(coverage);
        entry.setCommitmentAdjustment(money.commitmentAdjustment());
        entry.setNetSaving(money.net());
        entry.setPriceApproximate(oldRate.approximate() || newRate.approximate());
        entry.setNote("Resized " + resize.getOldInstanceType() + " -> " + resize.getNewInstanceType());
        ledgerRepository.save(entry);
        return true;
    }

    /** A stop caused by an operation takes its trigger's cause; anything else (state sync) is OTHER. */
    static SavingsCause causeOf(VmStateHistory h, Map<String, OperationTriggerSource> sources) {
        if (h.getOperationId() == null) return SavingsCause.OTHER;
        OperationTriggerSource source = sources.get(h.getOperationId());
        return source == null ? SavingsCause.OTHER : SavingsCause.fromTrigger(source);
    }

    /** Savings Plans coverage if reported, else RI coverage, else none. */
    static BigDecimal coverageOf(ReservationCoverageSnapshot s) {
        if (s.getSpCoveragePercent() != null) return s.getSpCoveragePercent();
        return s.getCoveragePercent();
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) set.add(value);
    }
}
