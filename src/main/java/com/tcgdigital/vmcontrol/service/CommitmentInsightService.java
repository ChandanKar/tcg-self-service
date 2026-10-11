package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CommitmentInsightDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * How schedules interact with Savings Plans and Reserved Instances (E27-T02), display only:
 * the value of stopped hours that commitments had already paid for (savings ledger), the
 * schedule-safe always-on floor (the 10th-percentile hour of On-Demand spend of AWS VMs over the
 * last 30 days), the latest coverage snapshot and AWS recommendations, and risk flags. EKS node
 * groups and VMs without a known price are left out of the floor.
 */
@Service
public class CommitmentInsightService {

    static final int WINDOW_DAYS = 30;
    static final double FLOOR_PERCENTILE = 0.10;
    static final BigDecimal OVER_COMMIT_MARGIN = new BigDecimal("1.1");
    static final BigDecimal UTILISATION_THRESHOLD = BigDecimal.valueOf(90);
    static final BigDecimal COVERAGE_THRESHOLD = BigDecimal.valueOf(80);
    /** Scheduled stop hours in the window that count as "schedules cover many hours". */
    static final BigDecimal MANY_SCHEDULED_HOURS = BigDecimal.valueOf(100);

    private final SavingsLedgerEntryRepository ledgerRepository;
    private final ReservationCoverageSnapshotRepository coverageRepository;
    private final CommitmentRecommendationRepository recommendationRepository;
    private final VmRepository vmRepository;
    private final VmStateHistoryRepository historyRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final PricingReferenceService pricingReferenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    Clock clock = Clock.systemUTC();

    public CommitmentInsightService(SavingsLedgerEntryRepository ledgerRepository,
                                    ReservationCoverageSnapshotRepository coverageRepository,
                                    CommitmentRecommendationRepository recommendationRepository,
                                    VmRepository vmRepository,
                                    VmStateHistoryRepository historyRepository,
                                    VmInventorySnapshotRepository inventoryRepository,
                                    PricingReferenceService pricingReferenceService) {
        this.ledgerRepository = ledgerRepository;
        this.coverageRepository = coverageRepository;
        this.recommendationRepository = recommendationRepository;
        this.vmRepository = vmRepository;
        this.historyRepository = historyRepository;
        this.inventoryRepository = inventoryRepository;
        this.pricingReferenceService = pricingReferenceService;
    }

    /** One VM's On-Demand rate and the intervals it ran in. */
    record RunningVm(BigDecimal hourlyRate, List<Interval> running) {}

    record Interval(Instant start, Instant end) {}

    @Transactional(readOnly = true)
    public CommitmentInsightDTO insights() {
        Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant start = end.minus(WINDOW_DAYS, ChronoUnit.DAYS);
        LocalDate today = LocalDate.ofInstant(end, ZoneOffset.UTC);

        // Stopped hours that commitments had already paid for (E17 ledger).
        List<SavingsLedgerEntry> ledger = ledgerRepository.findByLedgerDateBetweenOrderByLedgerDateAsc(
                today.minusDays(WINDOW_DAYS), today.minusDays(1));
        BigDecimal coveredValue = ledger.isEmpty() ? null : sum(ledger.stream().map(SavingsLedgerEntry::getCommitmentAdjustment).toList());
        BigDecimal gross = ledger.isEmpty() ? null : sum(ledger.stream().map(SavingsLedgerEntry::getGrossSaving).toList());
        BigDecimal scheduledHours = ledger.isEmpty() ? null : sum(ledger.stream()
                .filter(e -> e.getCause() == SavingsCause.SCHEDULE).map(SavingsLedgerEntry::getAvoidedHours).toList());

        List<RunningVm> floorVms = pricedAwsVms(start, end);
        BigDecimal floor = hourlyFloor(floorVms, start, end);

        CommitmentInsightDTO.Coverage coverage = coverageRepository.findTopByOrderBySnapshotDateDesc()
                .map(s -> new CommitmentInsightDTO.Coverage(s.getSnapshotDate().toLocalDate(), s.getCoveragePercent(),
                        s.getSpCoveragePercent(), s.getRiUtilizationPercent(), s.getSpUtilizationPercent()))
                .orElse(null);

        List<CommitmentInsightDTO.Recommendation> recommendations = recommendationRepository.findLatest().stream()
                .map(r -> new CommitmentInsightDTO.Recommendation(r.getRecType().name(), r.getTerm(), r.getPaymentOption(),
                        r.getLookbackDays(), r.getHourlyCommitment(), r.getUpfrontCost(), r.getEstimatedMonthlySavings(),
                        r.getEstimatedSavingsPercent(), r.getEstimatedOnDemandCost(), r.getGeneratedOn().toLocalDate(),
                        parse(r.getDetails())))
                .toList();

        return new CommitmentInsightDTO(WINDOW_DAYS, coverage, coveredValue, gross, scheduledHours, floor, floorVms.size(),
                "AWS EC2 VMs with a known price over the last " + WINDOW_DAYS + " days; EKS node groups are not included",
                recommendations, riskFlags(recommendations, coverage, floor, scheduledHours));
    }

    static List<CommitmentInsightDTO.RiskFlag> riskFlags(List<CommitmentInsightDTO.Recommendation> recommendations,
                                                         CommitmentInsightDTO.Coverage coverage, BigDecimal floor,
                                                         BigDecimal scheduledHours) {
        List<CommitmentInsightDTO.RiskFlag> flags = new ArrayList<>();
        if (floor != null) {
            BigDecimal limit = floor.multiply(OVER_COMMIT_MARGIN);
            for (CommitmentInsightDTO.Recommendation r : recommendations) {
                if (r.type().endsWith("_SP") && r.hourlyCommitment() != null && r.hourlyCommitment().compareTo(limit) > 0) {
                    flags.add(new CommitmentInsightDTO.RiskFlag("OVER_COMMIT",
                            "Recommendation exceeds the always-on floor; schedules may leave the commitment under-used", r.type()));
                }
            }
        }
        if (coverage != null) {
            List<String> low = new ArrayList<>();
            if (below(coverage.spUtilizationPercent(), UTILISATION_THRESHOLD)) low.add("Savings Plans");
            if (below(coverage.riUtilizationPercent(), UTILISATION_THRESHOLD)) low.add("Reserved Instances");
            if (!low.isEmpty()) {
                flags.add(new CommitmentInsightDTO.RiskFlag("UNDER_UTILISED",
                        "Existing commitments under-utilised (" + String.join(", ", low) + " below 90%)", null));
            }
            BigDecimal covered = max(coverage.spCoveragePercent(), coverage.riCoveragePercent());
            if (covered != null && covered.compareTo(COVERAGE_THRESHOLD) > 0
                    && scheduledHours != null && scheduledHours.compareTo(MANY_SCHEDULED_HOURS) >= 0) {
                flags.add(new CommitmentInsightDTO.RiskFlag("SCHEDULES_ALREADY_COVERED",
                        "Most scheduled hours are already covered by commitments; extra schedules save little compute", null));
            }
        }
        return flags;
    }

    /**
     * The On-Demand $/h that runs even in the quietest hours: for each whole hour of
     * {@code start..end}, the cost of the time each VM was running in it, then the 10th
     * percentile (nearest rank). Null when no VM is priced.
     */
    static BigDecimal hourlyFloor(List<RunningVm> vms, Instant start, Instant end) {
        int hours = (int) Duration.between(start, end).toHours();
        if (vms.isEmpty() || hours <= 0) {
            return null;
        }
        double[] spend = new double[hours];
        for (RunningVm vm : vms) {
            double rate = vm.hourlyRate().doubleValue();
            for (Interval interval : vm.running()) {
                Instant from = interval.start().isBefore(start) ? start : interval.start();
                Instant to = interval.end().isAfter(end) ? end : interval.end();
                while (from.isBefore(to)) {
                    int bucket = (int) Duration.between(start, from).toHours();
                    Instant bucketEnd = start.plus(bucket + 1L, ChronoUnit.HOURS);
                    Instant sliceEnd = to.isBefore(bucketEnd) ? to : bucketEnd;
                    spend[bucket] += rate * Duration.between(from, sliceEnd).toMillis() / 3_600_000d;
                    from = sliceEnd;
                }
            }
        }
        Arrays.sort(spend);
        int rank = Math.max(1, (int) Math.ceil(FLOOR_PERCENTILE * hours));
        return BigDecimal.valueOf(spend[rank - 1]).setScale(4, RoundingMode.HALF_UP);
    }

    /** Active AWS VMs with a known On-Demand rate and the intervals they ran in the window. */
    private List<RunningVm> pricedAwsVms(Instant start, Instant end) {
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() == CloudProvider.AWS).toList();
        if (vms.isEmpty()) {
            return List.of();
        }
        List<String> ids = vms.stream().map(Vm::getVmId).toList();
        Map<String, String> typeByVm = new HashMap<>();
        inventoryRepository.findInstanceTypesByVmIds(ids).forEach(p -> typeByVm.put(p.getVmId(), p.getInstanceType()));
        Timestamp from = Timestamp.from(start);
        Timestamp to = Timestamp.from(end);
        Map<String, List<VmStateHistory>> transitions = new HashMap<>();
        historyRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(ids, from, to)
                .forEach(h -> transitions.computeIfAbsent(h.getVm().getVmId(), k -> new ArrayList<>()).add(h));
        Map<String, VmStatus> before = new HashMap<>();
        historyRepository.findLatestBeforeForVms(ids, from).forEach(h -> before.put(h.getVm().getVmId(), h.getNewStatus()));

        List<RunningVm> priced = new ArrayList<>();
        for (Vm vm : vms) {
            PricingReferenceService.PriceLookupResult rate =
                    pricingReferenceService.lookupHourlyRate(CloudProvider.AWS.name(), typeByVm.get(vm.getVmId()), vm.getRegion());
            if (!rate.priceKnown()) {
                continue;
            }
            List<VmStateHistory> changes = transitions.getOrDefault(vm.getVmId(), List.of());
            VmStatus status = !changes.isEmpty() && changes.get(0).getPreviousStatus() != null
                    ? changes.get(0).getPreviousStatus()
                    : before.getOrDefault(vm.getVmId(), vm.getStatus());
            Instant cursor = vm.getCreatedAt() != null && vm.getCreatedAt().toInstant().isAfter(start)
                    ? vm.getCreatedAt().toInstant() : start;
            List<Interval> running = new ArrayList<>();
            for (VmStateHistory change : changes) {
                Instant at = change.getChangedAt().toInstant();
                if (at.isAfter(cursor)) {
                    if (status == VmStatus.RUNNING) {
                        running.add(new Interval(cursor, at));
                    }
                    cursor = at;
                }
                status = change.getNewStatus();
            }
            if (status == VmStatus.RUNNING && cursor.isBefore(end)) {
                running.add(new Interval(cursor, end));
            }
            priced.add(new RunningVm(rate.hourlyRate(), running));
        }
        return priced;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean below(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) < 0;
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.max(b);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
