package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleWindowDTO;
import com.tcgdigital.vmcontrol.dto.ScheduleSuggestionDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.service.idle.IdleEvaluator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Suggests schedule windows from an environment's real usage (E19-T05). Read only.
 *
 * <p>Builds a 168-slot hour-of-week activity matrix in the requested timezone: an hour counts as
 * active in a week when any VM was active in it by its metrics (the E16 idle thresholds: CPU at
 * or above the limit, or network above the budget), or, where no metrics exist for that hour,
 * when a VM was running by its state history (lower confidence). Hours active in at least a
 * quarter of the weeks are "on"; single-hour gaps are filled; each day's first and last on hour
 * become a window padded by 30 minutes; days with identical windows are merged.
 */
@Service
public class ScheduleSuggestionService {

    static final int MIN_HISTORY_DAYS = 14;
    static final int DEFAULT_WEEKS = 4;
    static final int MAX_WEEKS = 8;
    /** Share of the weeks in which an hour must be active to be "on". */
    static final double ACTIVE_SHARE = 0.25;
    private static final int SLOTS = 168;
    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final VmRepository vmRepository;
    private final VmStateHistoryRepository historyRepository;
    private final VmMetricSampleRepository sampleRepository;
    private final IdleStopRuleRepository idleStopRuleRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final PricingReferenceService pricingReferenceService;
    private final VmCostCalculator costCalculator;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public ScheduleSuggestionService(VmRepository vmRepository, VmStateHistoryRepository historyRepository,
                                     VmMetricSampleRepository sampleRepository, IdleStopRuleRepository idleStopRuleRepository,
                                     VmInventorySnapshotRepository inventoryRepository,
                                     PricingReferenceService pricingReferenceService, VmCostCalculator costCalculator) {
        this.vmRepository = vmRepository;
        this.historyRepository = historyRepository;
        this.sampleRepository = sampleRepository;
        this.idleStopRuleRepository = idleStopRuleRepository;
        this.inventoryRepository = inventoryRepository;
        this.pricingReferenceService = pricingReferenceService;
        this.costCalculator = costCalculator;
    }

    @Transactional(readOnly = true)
    public ScheduleSuggestionDTO suggest(String environmentId, ZoneId zone, int weeks) {
        if (weeks < 2 || weeks > MAX_WEEKS) {
            throw new ValidationException("weeks must be between 2 and " + MAX_WEEKS);
        }
        List<Vm> vms = vmRepository.findByEnvironmentId(environmentId);
        if (vms.isEmpty()) {
            throw new ValidationException("This environment has no active VMs");
        }
        List<String> ids = vms.stream().map(Vm::getVmId).toList();
        Instant end = ZonedDateTime.now(clock.withZone(zone)).truncatedTo(ChronoUnit.HOURS).toInstant();
        Instant requestedStart = end.minus(Duration.ofDays(7L * weeks));

        Map<String, VmStatus> seeds = new HashMap<>();
        historyRepository.findLatestBeforeForVms(ids, Timestamp.from(requestedStart))
                .forEach(h -> seeds.put(h.getVm().getVmId(), h.getNewStatus()));
        List<VmStateHistory> transitions = historyRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(
                ids, Timestamp.from(requestedStart), Timestamp.from(end));
        List<VmMetricSample> samples = sampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(
                ids, Timestamp.from(requestedStart), Timestamp.from(end));

        // How much history there is: all of it when a state is known from before the window.
        Instant historyStart = seeds.isEmpty() ? earliest(transitions, samples) : requestedStart;
        long days = historyStart == null ? 0 : Duration.between(historyStart, end).toDays();
        if (days < MIN_HISTORY_DAYS) {
            throw new ValidationException("Not enough history (need 2 weeks)");
        }
        int usedWeeks = (int) Math.min(weeks, days / 7);
        Instant start = end.minus(Duration.ofDays(7L * usedWeeks));

        Activity activity = activity(vms, seeds, transitions, samples, idleThresholds(environmentId), zone, start, usedWeeks);
        Proposal proposal = proposeWindows(activity.share());

        // VM-hours per week now and under the windows, and what the difference is worth.
        Map<String, List<VmStateHistory>> byVm = transitions.stream()
                .collect(Collectors.groupingBy(h -> h.getVm().getVmId()));
        Map<String, String> types = new HashMap<>();
        inventoryRepository.findInstanceTypesByVmIds(ids).stream().filter(p -> p.getInstanceType() != null)
                .forEach(p -> types.putIfAbsent(p.getVmId(), p.getInstanceType()));
        BigDecimal windowHours = BigDecimal.valueOf(proposal.hoursPerWeek());
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal suggested = BigDecimal.ZERO;
        BigDecimal saving = BigDecimal.ZERO;
        int unpriced = 0;
        for (Vm vm : vms) {
            VmStatus seed = seeds.getOrDefault(vm.getVmId(), firstPrevious(byVm.get(vm.getVmId())));
            BigDecimal hours = costCalculator.computeRuntimeHours(vm, seed, byVm.getOrDefault(vm.getVmId(), List.of()),
                    Timestamp.from(start), Timestamp.from(end)).divide(BigDecimal.valueOf(usedWeeks), 3, RoundingMode.HALF_UP);
            BigDecimal kept = hours.min(windowHours);
            current = current.add(hours);
            suggested = suggested.add(kept);
            String type = types.get(vm.getVmId());
            PricingReferenceService.PriceLookupResult price = type == null ? PricingReferenceService.PriceLookupResult.unknown()
                    : pricingReferenceService.lookupHourlyRate(vm.getProvider().name(), type, vm.getRegion());
            if (price.priceKnown()) {
                saving = saving.add(price.hourlyRate().multiply(hours.subtract(kept)));
            } else if (hours.compareTo(kept) > 0) {
                unpriced++;
            }
        }

        List<Double> share = new ArrayList<>(SLOTS);
        for (double v : activity.share()) {
            share.add(Math.round(v * 100) / 100.0);
        }
        return new ScheduleSuggestionDTO(environmentId, zone.getId(), usedWeeks, proposal.windows(), share,
                activity.confidence(usedWeeks), activity.metricsCoveragePercent(),
                current.setScale(1, RoundingMode.HALF_UP), suggested.setScale(1, RoundingMode.HALF_UP),
                saving.setScale(2, RoundingMode.HALF_UP), unpriced, proposal.alwaysOnDays());
    }

    // ============= Activity =============

    /** The idle thresholds of the environment's idle-stop rule, or the defaults (E16). */
    private IdleStopRule idleThresholds(String environmentId) {
        return idleStopRuleRepository.findByEnvironment(environmentId).stream()
                .filter(r -> r.getGroupId() == null).findFirst().orElseGet(IdleStopRule::new);
    }

    /** Share of the weeks each hour-of-week slot was active, and how much of it came from metrics. */
    record Activity(double[] share, int metricSlots, int fallbackSlots) {
        int metricsCoveragePercent() {
            int total = metricSlots + fallbackSlots;
            return total == 0 ? 0 : Math.round(100f * metricSlots / total);
        }

        String confidence(int weeks) {
            if (metricSlots == 0) {
                return "LOW";
            }
            return metricsCoveragePercent() >= 80 && weeks >= 4 ? "HIGH" : "MEDIUM";
        }
    }

    static Activity activity(List<Vm> vms, Map<String, VmStatus> seeds, List<VmStateHistory> transitions,
                             List<VmMetricSample> samples, IdleStopRule thresholds, ZoneId zone, Instant start, int weeks) {
        int hours = weeks * SLOTS;
        boolean[] hasMetric = new boolean[hours];
        boolean[] metricActive = new boolean[hours];
        boolean[] running = new boolean[hours];

        for (VmMetricSample s : samples) {
            int k = hourIndex(start, s.getSampleTime().toInstant(), hours);
            if (k < 0) {
                continue;
            }
            hasMetric[k] = true;
            if (isActive(s, thresholds)) {
                metricActive[k] = true;
            }
        }

        Map<String, List<VmStateHistory>> byVm = transitions.stream().collect(Collectors.groupingBy(h -> h.getVm().getVmId()));
        Instant end = start.plus(Duration.ofHours(hours));
        for (Vm vm : vms) {
            List<VmStateHistory> own = byVm.getOrDefault(vm.getVmId(), List.of());
            VmStatus status = seeds.getOrDefault(vm.getVmId(), firstPrevious(own));
            Instant cursor = start;
            for (VmStateHistory h : own) {
                Instant at = h.getChangedAt().toInstant();
                if (at.isBefore(cursor)) {
                    status = h.getNewStatus();
                    continue;
                }
                if (status == VmStatus.RUNNING) {
                    mark(running, start, cursor, at, hours);
                }
                status = h.getNewStatus();
                cursor = at;
            }
            if (status == VmStatus.RUNNING) {
                mark(running, start, cursor, end, hours);
            }
        }

        double[] share = new double[SLOTS];
        int metricSlots = 0;
        int fallbackSlots = 0;
        for (int k = 0; k < hours; k++) {
            boolean active;
            if (hasMetric[k]) {
                metricSlots++;
                active = metricActive[k];
            } else {
                active = running[k];
                if (active) {
                    fallbackSlots++;
                }
            }
            if (active) {
                share[slot(start.plus(Duration.ofHours(k)), zone)] += 1.0 / weeks;
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            share[i] = Math.min(1.0, share[i]);
        }
        return new Activity(share, metricSlots, fallbackSlots);
    }

    private static boolean isActive(VmMetricSample s, IdleStopRule thresholds) {
        if (s.getCpuUtilization() != null && s.getCpuUtilization().compareTo(thresholds.getCpuMaxPercent()) >= 0) {
            return true;
        }
        long period = s.getPeriodSeconds() != null && s.getPeriodSeconds() > 0 ? s.getPeriodSeconds() : 300;
        long network = (s.getNetworkInBytes() == null ? 0 : s.getNetworkInBytes())
                + (s.getNetworkOutBytes() == null ? 0 : s.getNetworkOutBytes());
        return network >= IdleEvaluator.networkBudget(thresholds, period);
    }

    /** Marks every hour that [from, to) touches. */
    private static void mark(boolean[] hours, Instant start, Instant from, Instant to, int count) {
        if (!to.isAfter(from)) {
            return;
        }
        long first = Math.max(0, Duration.between(start, from).toHours());
        long lastExclusive = Math.min(count, (Duration.between(start, to).toMillis() + 3_599_999) / 3_600_000);
        for (long k = first; k < lastExclusive; k++) {
            hours[(int) k] = true;
        }
    }

    private static int hourIndex(Instant start, Instant at, int count) {
        if (at.isBefore(start)) {
            return -1;
        }
        long k = Duration.between(start, at).toHours();
        return k < count ? (int) k : -1;
    }

    /** 0 = Monday 00:00 .. 167 = Sunday 23:00, in {@code zone}. */
    static int slot(Instant hourStart, ZoneId zone) {
        ZonedDateTime local = hourStart.atZone(zone);
        return (local.getDayOfWeek().getValue() - 1) * 24 + local.getHour();
    }

    private static VmStatus firstPrevious(List<VmStateHistory> transitions) {
        return transitions == null || transitions.isEmpty() ? null : transitions.get(0).getPreviousStatus();
    }

    private static Instant earliest(List<VmStateHistory> transitions, List<VmMetricSample> samples) {
        Instant first = null;
        for (VmStateHistory h : transitions) {
            Instant at = h.getChangedAt().toInstant();
            first = first == null || at.isBefore(first) ? at : first;
        }
        for (VmMetricSample s : samples) {
            Instant at = s.getSampleTime().toInstant();
            first = first == null || at.isBefore(first) ? at : first;
        }
        return first;
    }

    // ============= Windows =============

    /** Proposed windows, their on-hours per week, and the days used around the clock. */
    record Proposal(List<AutomationRuleWindowDTO> windows, double hoursPerWeek, List<String> alwaysOnDays) {}

    static Proposal proposeWindows(double[] share) {
        Map<String, List<String>> daysByWindow = new LinkedHashMap<>();
        Map<String, String[]> timesByWindow = new HashMap<>();
        List<String> alwaysOn = new ArrayList<>();
        double hours = 0;
        for (int d = 0; d < 7; d++) {
            boolean[] on = new boolean[24];
            for (int h = 0; h < 24; h++) {
                on[h] = share[d * 24 + h] >= ACTIVE_SHARE;
            }
            boolean[] filled = on.clone();
            for (int h = 1; h < 23; h++) {
                if (!on[h] && on[h - 1] && on[h + 1]) {
                    filled[h] = true;
                }
            }
            int first = -1;
            int last = -1;
            for (int h = 0; h < 24; h++) {
                if (filled[h]) {
                    first = first < 0 ? h : first;
                    last = h;
                }
            }
            if (first < 0) {
                continue; // not used: no window, so the rule neither starts nor stops that day
            }
            // Start 30 minutes before the first used hour, stop 30 minutes after the last; a day
            // used from midnight has no start, one used until midnight no stop.
            String start = first == 0 ? null : hhmm(first * 60 - 30);
            String stop = last == 23 ? null : hhmm((last + 1) * 60 + 30);
            if (start == null && stop == null) {
                alwaysOn.add(WEEK.get(d));
                hours += 24;
                continue;
            }
            int from = start == null ? 0 : first * 60 - 30;
            int to = stop == null ? 24 * 60 : (last + 1) * 60 + 30;
            hours += (to - from) / 60.0;
            String key = start + "|" + stop;
            daysByWindow.computeIfAbsent(key, k -> new ArrayList<>()).add(WEEK.get(d));
            timesByWindow.put(key, new String[] {start, stop});
        }
        List<AutomationRuleWindowDTO> windows = daysByWindow.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> WEEK.indexOf(e.getValue().get(0))))
                .map(e -> new AutomationRuleWindowDTO(e.getValue(), timesByWindow.get(e.getKey())[0], timesByWindow.get(e.getKey())[1]))
                .toList();
        return new Proposal(windows, hours, alwaysOn);
    }

    private static String hhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
