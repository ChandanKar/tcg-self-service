package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.TeamQuota;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamQuotaRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Team quotas (E25-T07): a monthly limit of running VM-hours or dollars per team. A start is
 * projected onto this month's usage (VM-hours: VMs to start x hours; dollars: the cost preview);
 * past the limit the quota's enforcement applies (WARN lets it run with a warning,
 * REQUIRE_APPROVAL holds it for approval, BLOCK refuses it). Off unless quotas.enabled.
 */
@Service
public class QuotaService {

    private final TeamQuotaRepository quotaRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final QuotaUsageService usageService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Value("${quotas.enabled:false}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean live(String key, boolean current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    public QuotaService(TeamQuotaRepository quotaRepository, TeamRepository teamRepository, TeamMemberRepository memberRepository,
                        QuotaUsageService usageService, NotificationService notificationService, AuditService auditService) {
        this.quotaRepository = quotaRepository;
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.usageService = usageService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    public boolean isEnabled() {
        return live("quotas.enabled", enabled);
    }

    // ============= Configuration =============

    public record QuotaView(String quotaId, String metric, BigDecimal limit, String enforcement, int warnPercent, boolean active,
                            BigDecimal used, BigDecimal percent) {}

    public record QuotaRequest(String metric, BigDecimal limit, String enforcement, Integer warnPercent, Boolean active) {}

    /** The team's quotas with this month's usage. */
    @Transactional(readOnly = true)
    public List<QuotaView> usage(String teamId) {
        team(teamId);
        List<TeamQuota> quotas = quotaRepository.findByTeamIdOrderByMetricAsc(teamId);
        if (quotas.isEmpty()) {
            return List.of();
        }
        QuotaUsageService.Usage usage = usageService.monthToDate(teamId);
        return quotas.stream().map(q -> view(q, used(q.getMetric(), usage))).toList();
    }

    /** Replaces the team's quotas (one per metric); a metric left out is removed. */
    @Transactional
    public List<QuotaView> replace(String teamId, List<QuotaRequest> requests, String actorId) {
        Team team = team(teamId);
        Map<TeamQuota.Metric, QuotaRequest> wanted = new EnumMap<>(TeamQuota.Metric.class);
        for (QuotaRequest r : requests == null ? List.<QuotaRequest>of() : requests) {
            TeamQuota.Metric metric = parse(TeamQuota.Metric.class, r.metric(), "metric must be VM_HOURS or COST");
            if (wanted.put(metric, r) != null) {
                throw new ValidationException("One quota per metric");
            }
            if (r.limit() == null || r.limit().signum() <= 0) {
                throw new ValidationException("The limit must be above 0");
            }
            parse(TeamQuota.Enforcement.class, r.enforcement(), "enforcement must be WARN, REQUIRE_APPROVAL or BLOCK");
            if (r.warnPercent() != null && (r.warnPercent() < 1 || r.warnPercent() > 100)) {
                throw new ValidationException("Warn at 1 to 100 percent");
            }
        }
        Map<TeamQuota.Metric, TeamQuota> existing = new EnumMap<>(TeamQuota.Metric.class);
        quotaRepository.findByTeamIdOrderByMetricAsc(teamId).forEach(q -> existing.put(q.getMetric(), q));
        existing.forEach((metric, q) -> {
            if (!wanted.containsKey(metric)) {
                quotaRepository.delete(q);
            }
        });
        wanted.forEach((metric, r) -> {
            TeamQuota q = existing.getOrDefault(metric, new TeamQuota());
            if (q.getQuotaId() == null) {
                q.setQuotaId(UUID.randomUUID().toString());
                q.setTeamId(teamId);
                q.setMetric(metric);
            }
            q.setLimitValue(r.limit().setScale(2, RoundingMode.HALF_UP));
            q.setEnforcement(TeamQuota.Enforcement.valueOf(r.enforcement().trim().toUpperCase(Locale.ROOT)));
            q.setWarnPercent(r.warnPercent() == null ? 80 : r.warnPercent());
            q.setIsActive(r.active() == null || r.active());
            quotaRepository.save(q);
        });
        quotaRepository.flush();
        auditService.logAction(actorId, AuditAction.TEAM_QUOTA_CHANGED, "team", teamId, team.getDisplayName(),
                wanted.isEmpty() ? "Removed all quotas" : String.join("; ", wanted.entrySet().stream().map(e ->
                        e.getKey() + " " + e.getValue().limit().toPlainString() + " (" + e.getValue().enforcement() + ")").toList()));
        return usage(teamId);
    }

    /** A team the user belongs to, with its quotas and this month's usage (E25-T08). */
    public record TeamUsage(String teamId, String teamName, List<QuotaView> quotas) {}

    /** The user's teams that have quotas. */
    @Transactional(readOnly = true)
    public List<TeamUsage> mine(String userId) {
        List<TeamUsage> out = new ArrayList<>();
        for (String teamId : memberRepository.findTeamIdsByUserId(userId)) {
            List<QuotaView> quotas = usage(teamId);
            if (!quotas.isEmpty()) {
                out.add(new TeamUsage(teamId, team(teamId).getDisplayName(), quotas));
            }
        }
        out.sort(Comparator.comparing(TeamUsage::teamName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ============= Checking a start =============

    public enum Outcome { OK, WARN, REQUIRE_APPROVAL, BLOCK }

    /** The most severe outcome over the team's quotas, with a message per quota that is near or past its limit. */
    public record QuotaCheck(Outcome outcome, List<String> messages) {
        public static QuotaCheck ok() {
            return new QuotaCheck(Outcome.OK, List.of());
        }
    }

    /** vmCount VMs starting for hours, costing about estimatedCost. */
    @Transactional(readOnly = true)
    public QuotaCheck check(String teamId, int vmCount, int hours, BigDecimal estimatedCost) {
        if (!live("quotas.enabled", enabled) || teamId == null) {
            return QuotaCheck.ok();
        }
        List<TeamQuota> quotas = quotaRepository.findByTeamIdAndIsActiveTrue(teamId);
        if (quotas.isEmpty()) {
            return QuotaCheck.ok();
        }
        QuotaUsageService.Usage usage = usageService.monthToDate(teamId);
        Outcome outcome = Outcome.OK;
        List<String> messages = new ArrayList<>();
        for (TeamQuota q : quotas) {
            BigDecimal used = used(q.getMetric(), usage);
            BigDecimal adds = q.getMetric() == TeamQuota.Metric.VM_HOURS
                    ? BigDecimal.valueOf((long) vmCount * hours)
                    : estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
            BigDecimal projected = used.add(adds);
            String unit = q.getMetric() == TeamQuota.Metric.VM_HOURS ? " VM-hours" : "";
            String amount = q.getMetric() == TeamQuota.Metric.VM_HOURS ? plain(used) + " of " + plain(q.getLimitValue()) + unit
                    : "$" + plain(used) + " of $" + plain(q.getLimitValue());
            String adding = q.getMetric() == TeamQuota.Metric.VM_HOURS ? plain(adds) + unit : "$" + plain(adds);
            if (projected.compareTo(q.getLimitValue()) > 0) {
                Outcome o = switch (q.getEnforcement()) {
                    case WARN -> Outcome.WARN;
                    case REQUIRE_APPROVAL -> Outcome.REQUIRE_APPROVAL;
                    case BLOCK -> Outcome.BLOCK;
                };
                messages.add("Team quota exceeded: " + amount + " used; this start adds " + adding);
                if (o.ordinal() > outcome.ordinal()) {
                    outcome = o;
                }
            } else if (percent(projected, q.getLimitValue()).compareTo(BigDecimal.valueOf(q.getWarnPercent())) >= 0) {
                messages.add("Team quota nearly used: " + amount + " used; this start adds " + adding);
                if (outcome == Outcome.OK) {
                    outcome = Outcome.WARN;
                }
            }
        }
        return new QuotaCheck(outcome, messages);
    }

    // ============= Monthly warnings =============

    /** Warns team members once a month when usage reaches warn_percent and again at 100%. */
    @Transactional
    public int sendWarnings() {
        if (!live("quotas.enabled", enabled)) {
            return 0;
        }
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        Map<String, QuotaUsageService.Usage> usageByTeam = new HashMap<>();
        int sent = 0;
        for (TeamQuota q : quotaRepository.findByIsActiveTrue()) {
            QuotaUsageService.Usage usage = usageByTeam.computeIfAbsent(q.getTeamId(), usageService::monthToDate);
            BigDecimal used = used(q.getMetric(), usage);
            BigDecimal pct = percent(used, q.getLimitValue());
            int level = pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100
                    : pct.compareTo(BigDecimal.valueOf(q.getWarnPercent())) >= 0 ? q.getWarnPercent() : 0;
            if (level == 0 || quotaRepository.recordAlert(q.getQuotaId(), month, level) == 0) {
                continue;
            }
            Team team = teamRepository.findById(q.getTeamId()).orElse(null);
            if (team == null) {
                continue;
            }
            String what = q.getMetric() == TeamQuota.Metric.VM_HOURS ? plain(used) + " of " + plain(q.getLimitValue()) + " VM-hours"
                    : "$" + plain(used) + " of $" + plain(q.getLimitValue());
            String title = (level >= 100 ? "Team quota used up: " : "Team quota at " + level + "%: ") + team.getDisplayName();
            String message = team.getDisplayName() + " has used " + what + " this month."
                    + (level >= 100 ? " Further starts " + (q.getEnforcement() == TeamQuota.Enforcement.BLOCK ? "are blocked"
                    : q.getEnforcement() == TeamQuota.Enforcement.REQUIRE_APPROVAL ? "need approval" : "are allowed with a warning") + "." : "");
            List<String> members = memberRepository.findByTeamId(q.getTeamId()).stream().map(m -> m.getUserId()).toList();
            notificationService.notifyQuotaThreshold(members, q.getTeamId(), title, message);
            sent++;
        }
        return sent;
    }

    // ============= Helpers =============

    static BigDecimal used(TeamQuota.Metric metric, QuotaUsageService.Usage usage) {
        return metric == TeamQuota.Metric.VM_HOURS ? usage.vmHours() : usage.cost();
    }

    static BigDecimal percent(BigDecimal value, BigDecimal limit) {
        if (limit == null || limit.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= 0 ? v.setScale(0, RoundingMode.HALF_UP).toPlainString()
                : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static QuotaView view(TeamQuota q, BigDecimal used) {
        return new QuotaView(q.getQuotaId(), q.getMetric().name(), q.getLimitValue(), q.getEnforcement().name(), q.getWarnPercent(),
                Boolean.TRUE.equals(q.getIsActive()), used, percent(used, q.getLimitValue()));
    }

    private Team team(String teamId) {
        return teamRepository.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(message);
        }
    }
}
