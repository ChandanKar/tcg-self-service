package com.tcgdigital.vmcontrol.service.idle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.IdleStopStatusDTO;
import com.tcgdigital.vmcontrol.model.IdleStopEvent;
import com.tcgdigital.vmcontrol.model.IdleStopOutcome;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.IdleStopEventRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What idle auto-stop would have saved (dry run) and saved (enforce) over the last days
 * (E16-T04, G5).
 * <ul>
 *   <li>WOULD_STOP: saving per hour x hours until the scope next showed activity (a busy sample
 *       or any state change of its VMs), capped at 24 h per episode and at now.</li>
 *   <li>STOPPED: saving per hour x hours until one of the VMs started again, capped at now.</li>
 * </ul>
 */
@Service
public class IdleStopSummaryService {

    static final Duration EPISODE_CAP = Duration.ofHours(24);
    static final int MAX_DAYS = 31;

    private final IdleStopEventRepository eventRepository;
    private final IdleStopRuleRepository ruleRepository;
    private final VmMetricSampleRepository sampleRepository;
    private final VmStateHistoryRepository historyRepository;
    private final ObjectMapper json = new ObjectMapper();
    private Clock clock = Clock.systemUTC();

    public IdleStopSummaryService(IdleStopEventRepository eventRepository, IdleStopRuleRepository ruleRepository,
                                  VmMetricSampleRepository sampleRepository, VmStateHistoryRepository historyRepository) {
        this.eventRepository = eventRepository;
        this.ruleRepository = ruleRepository;
        this.sampleRepository = sampleRepository;
        this.historyRepository = historyRepository;
    }

    public record Summary(int wouldStopEpisodes, BigDecimal wouldHaveSaved, int stoppedCount, BigDecimal savedEstimate,
                          List<IdleStopStatusDTO.SavingEntry> stoppedSavings) {}

    public Summary summarize(String environmentId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        return summarizeSince(environmentId, clock.instant().minus(Duration.ofDays(window)));
    }

    /** The same summary for events since {@code from} (e.g. the start of the month, E18). */
    public Summary summarizeSince(String environmentId, Instant since) {
        Instant now = clock.instant();
        List<IdleStopEvent> events = eventRepository.findByEnvironmentIdAndEvaluatedAtBetweenOrderByEvaluatedAtDesc(
                environmentId, Timestamp.from(since), Timestamp.from(now));
        Map<String, IdleStopRule> rules = ruleRepository.findByEnvironment(environmentId).stream()
                .collect(Collectors.toMap(IdleStopRule::getRuleId, Function.identity()));

        int episodes = 0;
        BigDecimal wouldHaveSaved = BigDecimal.ZERO;
        int stopped = 0;
        BigDecimal saved = BigDecimal.ZERO;
        List<IdleStopStatusDTO.SavingEntry> entries = new ArrayList<>();
        for (IdleStopEvent event : events) {
            BigDecimal perHour = event.getProjectedSavingPerHour() == null ? BigDecimal.ZERO : event.getProjectedSavingPerHour();
            List<String> vmIds = vmIds(event);
            Instant from = event.getEvaluatedAt().toInstant();
            if (event.getOutcome() == IdleStopOutcome.WOULD_STOP) {
                episodes++;
                Instant until = min(nextActivity(vmIds, from, rules.get(event.getRuleId()), now), from.plus(EPISODE_CAP));
                wouldHaveSaved = wouldHaveSaved.add(perHour.multiply(hours(from, until)));
            } else if (event.getOutcome() == IdleStopOutcome.STOPPED) {
                stopped++;
                BigDecimal hours = hours(from, nextStart(vmIds, from, now));
                BigDecimal amount = perHour.multiply(hours).setScale(2, RoundingMode.HALF_UP);
                saved = saved.add(amount);
                entries.add(new IdleStopStatusDTO.SavingEntry(event.getEventId(), hours, amount));
            }
        }
        return new Summary(episodes, wouldHaveSaved.setScale(2, RoundingMode.HALF_UP), stopped,
                saved.setScale(2, RoundingMode.HALF_UP), entries);
    }

    /** First busy sample or state change of the VMs after {@code from}; {@code now} if none. */
    private Instant nextActivity(List<String> vmIds, Instant from, IdleStopRule rule, Instant now) {
        if (vmIds.isEmpty()) {
            return now;
        }
        Instant end = min(now, from.plus(EPISODE_CAP));
        Optional<Instant> busy = rule == null ? Optional.empty() : sampleRepository
                .findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(vmIds, Timestamp.from(from), Timestamp.from(end))
                .stream()
                .filter(s -> isBusy(s, rule))
                .map(s -> s.getSampleTime().toInstant())
                .findFirst();
        Optional<Instant> change = historyRepository.findByVmIdsChangedAfter(vmIds, Timestamp.from(from)).stream()
                .map(h -> h.getChangedAt().toInstant())
                .findFirst();
        Instant next = now;
        if (busy.isPresent()) next = min(next, busy.get());
        if (change.isPresent()) next = min(next, change.get());
        return next;
    }

    /** First time one of the VMs started again after the stop; {@code now} if none. */
    private Instant nextStart(List<String> vmIds, Instant from, Instant now) {
        if (vmIds.isEmpty()) {
            return now;
        }
        return historyRepository.findByVmIdsChangedAfter(vmIds, Timestamp.from(from)).stream()
                .filter(h -> h.getNewStatus() == VmStatus.RUNNING || h.getNewStatus() == VmStatus.STARTING)
                .map(VmStateHistory::getChangedAt)
                .map(Timestamp::toInstant)
                .findFirst()
                .map(t -> min(t, now))
                .orElse(now);
    }

    private static boolean isBusy(VmMetricSample s, IdleStopRule rule) {
        if (s.getCpuUtilization() != null && s.getCpuUtilization().compareTo(rule.getCpuMaxPercent()) >= 0) {
            return true;
        }
        long period = s.getPeriodSeconds() != null && s.getPeriodSeconds() > 0 ? s.getPeriodSeconds() : 300;
        long network = (s.getNetworkInBytes() == null ? 0 : s.getNetworkInBytes())
                + (s.getNetworkOutBytes() == null ? 0 : s.getNetworkOutBytes());
        return network >= IdleEvaluator.networkBudget(rule, period);
    }

    /** The VMs recorded in the event's evidence. */
    private List<String> vmIds(IdleStopEvent event) {
        if (event.getEvidenceJson() == null) {
            return List.of();
        }
        try {
            JsonNode vms = json.readTree(event.getEvidenceJson()).path("vms");
            List<String> ids = new ArrayList<>();
            vms.forEach(v -> ids.add(v.path("vmId").asText()));
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static BigDecimal hours(Instant from, Instant until) {
        long seconds = Math.max(0, Duration.between(from, until).getSeconds());
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 4, RoundingMode.HALF_UP);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
