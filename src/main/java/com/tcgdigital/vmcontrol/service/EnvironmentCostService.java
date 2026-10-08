package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EnvironmentCostDTO;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.idle.IdleStopSummaryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An environment's cost for its owners and users (E18-T01, G7): month to date (complete days from
 * cost_daily_snapshot + today's live estimate), the trend, a run-rate forecast, the top VMs,
 * savings and the next scheduled stop/start.
 *
 * <p>Days follow the snapshot job's calendar (the server's local date), so a day is never counted
 * twice or skipped between the snapshots and the live estimate.
 */
@Service
public class EnvironmentCostService {

    static final int TREND_DAYS = 30;
    static final int RUN_RATE_DAYS = 7;
    static final int TOP_VMS = 5;

    private final CostDailySnapshotRepository snapshotRepository;
    private final VmRepository vmRepository;
    private final VmGroupRepository groupRepository;
    private final CostDataProvider costDataProvider;
    private final SecurityService securityService;
    private final UserService userService;
    private final AutomationRuleService automationRuleService;
    private final IdleStopSummaryService idleStopSummaryService;

    static final int SPARKLINE_DAYS = 14;
    static final int ADMIN_LIST_LIMIT = 50;
    static final String GROUPS_HINT = "Cost is shown per environment; you have access to some groups only";

    private Clock clock = Clock.systemDefaultZone();
    private final com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository;
    private final com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository accessRepository;

    public EnvironmentCostService(CostDailySnapshotRepository snapshotRepository, VmRepository vmRepository,
                                  VmGroupRepository groupRepository, CostDataProvider costDataProvider,
                                  SecurityService securityService, UserService userService,
                                  AutomationRuleService automationRuleService,
                                  IdleStopSummaryService idleStopSummaryService,
                                  com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository,
                                  com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository accessRepository) {
        this.environmentRepository = environmentRepository;
        this.accessRepository = accessRepository;
        this.snapshotRepository = snapshotRepository;
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.costDataProvider = costDataProvider;
        this.securityService = securityService;
        this.userService = userService;
        this.automationRuleService = automationRuleService;
        this.idleStopSummaryService = idleStopSummaryService;
    }

    /** The cost view for the current user; the caller has checked they can view the environment. */
    @Transactional(readOnly = true)
    public EnvironmentCostDTO getCost(String environmentId) {
        return getCost(environmentId, userService.getCurrentUser());
    }

    @Transactional(readOnly = true)
    public EnvironmentCostDTO getCost(String environmentId, User user) {
        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        Instant monthStartInstant = monthStart.atStartOfDay(zone).toInstant();
        Instant todayStart = today.atStartOfDay(zone).toInstant();

        List<Vm> envVms = vmRepository.findByEnvironmentIdFetchGroupAndEnvironment(environmentId);
        List<String> allGroupIds = groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environmentId)
                .stream().map(VmGroup::getGroupId).toList();
        Set<String> visible = new HashSet<>(securityService.getVisibleGroupIds(user, environmentId));
        boolean full = visible.containsAll(allGroupIds);
        List<Vm> scopeVms = full ? envVms
                : envVms.stream().filter(vm -> visible.contains(vm.getGroup().getGroupId())).toList();

        // One estimate over the month for the top VMs (and the GROUPS total).
        Map<String, CostDataProvider.VmCostEstimate> monthByVm = scopeVms.isEmpty() ? Map.of()
                : costDataProvider.estimateCosts(scopeVms, Timestamp.from(monthStartInstant), Timestamp.from(now));
        List<EnvironmentCostDTO.TopVm> topVms = scopeVms.stream()
                .map(vm -> {
                    CostDataProvider.VmCostEstimate e = monthByVm.get(vm.getVmId());
                    BigDecimal cost = e == null || e.cost() == null ? BigDecimal.ZERO : e.cost();
                    return new EnvironmentCostDTO.TopVm(vm.getVmId(), vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName(),
                            money(cost), vm.getStatus() == null ? null : vm.getStatus().name(), e != null && e.costKnown());
                })
                .sorted(Comparator.comparing(EnvironmentCostDTO.TopVm::mtdCost).reversed())
                .limit(TOP_VMS)
                .toList();

        AutomationRuleService.NextFirings firings = automationRuleService.nextScheduledFirings(environmentId, now);
        EnvironmentCostDTO.ScheduleStatus schedule = new EnvironmentCostDTO.ScheduleStatus(
                firings.ruleCount(), firings.nextStop(), firings.nextStart());

        if (!full) {
            BigDecimal mtd = monthByVm.values().stream().map(CostDataProvider.VmCostEstimate::cost)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new EnvironmentCostDTO(environmentId, "GROUPS", monthStart, money(mtd), null, 0, null, null, null,
                    null, List.of(), topVms, null, null, schedule, null);
        }

        // Complete days: snapshots; today: the live estimate.
        LocalDate yesterday = today.minusDays(1);
        LocalDate previousMonthStart = monthStart.minusMonths(1);
        LocalDate trendStart = today.minusDays(TREND_DAYS);
        LocalDate queryFrom = previousMonthStart.isBefore(trendStart) ? previousMonthStart : trendStart;
        List<CostDailySnapshot> snapshots = snapshotRepository
                .findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        environmentId, Date.valueOf(queryFrom), Date.valueOf(yesterday));

        BigDecimal completeEstimated = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        int actualDays = 0;
        for (CostDailySnapshot s : snapshots) {
            LocalDate day = s.getSnapshotDate().toLocalDate();
            if (!day.isBefore(monthStart)) {
                completeEstimated = completeEstimated.add(nz(s.getEstimatedCost()));
                if (s.getActualCost() != null) {
                    actual = actual.add(s.getActualCost());
                    actualDays++;
                }
            }
        }
        BigDecimal todayPartial = envVms.isEmpty() ? BigDecimal.ZERO
                : costDataProvider.estimateCosts(envVms, Timestamp.from(todayStart), Timestamp.from(now)).values().stream()
                        .map(CostDataProvider.VmCostEstimate::cost).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mtd = completeEstimated.add(todayPartial);

        // Last month over the same complete days (1st .. yesterday's day of month).
        BigDecimal previous = null;
        if (today.getDayOfMonth() > 1) {
            int lastDay = Math.min(yesterday.getDayOfMonth(), previousMonthStart.lengthOfMonth());
            LocalDate previousEnd = previousMonthStart.withDayOfMonth(lastDay);
            List<CostDailySnapshot> previousDays = snapshots.stream()
                    .filter(s -> !s.getSnapshotDate().toLocalDate().isBefore(previousMonthStart)
                            && !s.getSnapshotDate().toLocalDate().isAfter(previousEnd))
                    .toList();
            if (!previousDays.isEmpty()) {
                previous = previousDays.stream().map(s -> nz(s.getEstimatedCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
            }
        }
        BigDecimal changePercent = previous == null || previous.signum() == 0 ? null
                : completeEstimated.subtract(previous).multiply(BigDecimal.valueOf(100))
                        .divide(previous, 1, RoundingMode.HALF_UP);

        // Run rate: the average of the last 7 complete days, over the days left this month.
        List<CostDailySnapshot> runRateDays = snapshots.stream()
                .filter(s -> !s.getSnapshotDate().toLocalDate().isBefore(today.minusDays(RUN_RATE_DAYS)))
                .toList();
        BigDecimal forecast = null;
        if (!runRateDays.isEmpty()) {
            BigDecimal average = runRateDays.stream().map(s -> nz(s.getEstimatedCost())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(runRateDays.size()), 4, RoundingMode.HALF_UP);
            int daysLeft = today.lengthOfMonth() - today.getDayOfMonth();
            forecast = mtd.add(average.multiply(BigDecimal.valueOf(daysLeft)));
        }

        List<EnvironmentCostDTO.DailyCost> daily = snapshots.stream()
                .filter(s -> !s.getSnapshotDate().toLocalDate().isBefore(trendStart))
                .map(s -> new EnvironmentCostDTO.DailyCost(s.getSnapshotDate().toLocalDate(), money(nz(s.getEstimatedCost())),
                        s.getActualCost() == null ? null : money(s.getActualCost())))
                .toList();

        // Savings: no savings ledger yet (E17), so the realised idle auto-stop savings this month (E16).
        BigDecimal savings = idleStopSummaryService.summarizeSince(environmentId, monthStartInstant).savedEstimate();

        return new EnvironmentCostDTO(environmentId, "FULL", monthStart, money(mtd),
                actualDays == 0 ? null : money(actual), actualDays, previous == null ? null : money(previous), changePercent,
                forecast == null ? null : money(forecast), forecast == null ? null : "run rate of the last " + runRateDays.size() + " day(s)",
                daily, topVms, money(savings), "idle auto-stop", schedule, null);
    }

    /**
     * The cost list for My Account (E18-T02): every environment the user can see, from snapshots
     * only, in a fixed number of queries. Global admins and env admins see all active
     * environments (at most 50, highest month to date first); others see those they hold a grant
     * on. A group-only grant shows no environment-level cost.
     */
    @Transactional(readOnly = true)
    public com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO.Response getMyEnvironmentsCost(User user) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate previousMonthStart = monthStart.minusMonths(1);
        LocalDate sparkStart = today.minusDays(SPARKLINE_DAYS - 1);

        boolean globalAdmin = user != null && (user.isAdmin() || user.isEnvAdmin());
        Map<String, com.tcgdigital.vmcontrol.model.Environment> envs = new java.util.LinkedHashMap<>();
        Map<String, com.tcgdigital.vmcontrol.model.AccessLevel> levels = new java.util.HashMap<>();
        if (globalAdmin) {
            environmentRepository.findByIsActiveTrue().forEach(e -> {
                envs.put(e.getEnvironmentId(), e);
                levels.put(e.getEnvironmentId(), com.tcgdigital.vmcontrol.model.AccessLevel.ADMIN);
            });
        } else if (user != null) {
            for (com.tcgdigital.vmcontrol.model.EnvironmentAccess grant
                    : accessRepository.findActiveAccessByUser(user.getUserId(), Timestamp.from(now))) {
                com.tcgdigital.vmcontrol.model.Environment env = grant.getEnvironment();
                if (!Boolean.TRUE.equals(env.getIsActive())) {
                    continue;
                }
                envs.putIfAbsent(env.getEnvironmentId(), env);
                if (grant.getScopeType() == com.tcgdigital.vmcontrol.model.AccessScopeType.ENVIRONMENT) {
                    levels.merge(env.getEnvironmentId(), grant.getAccessLevel(),
                            (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
                }
            }
        }
        List<String> ids = List.copyOf(envs.keySet());
        if (ids.isEmpty()) {
            return new com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO.Response(List.of(), false, 0, ADMIN_LIST_LIMIT);
        }

        LocalDate from = previousMonthStart.isBefore(sparkStart) ? previousMonthStart : sparkStart;
        Map<String, Map<LocalDate, BigDecimal>> daily = new java.util.HashMap<>();
        for (CostDailySnapshotRepository.EnvironmentDailyCost row
                : snapshotRepository.findDailyByEnvironmentIdsBetween(ids, Date.valueOf(from), Date.valueOf(today))) {
            daily.computeIfAbsent(row.getEnvironmentId(), k -> new java.util.HashMap<>())
                    .merge(row.getSnapshotDate().toLocalDate(), nz(row.getEstimatedCost()), BigDecimal::add);
        }
        Map<String, VmRepository.EnvironmentVmCounts> counts = vmRepository
                .countVmsGroupedByEnvironment(ids, com.tcgdigital.vmcontrol.model.VmStatus.RUNNING).stream()
                .collect(java.util.stream.Collectors.toMap(VmRepository.EnvironmentVmCounts::getEnvironmentId, c -> c));
        Map<String, AutomationRuleService.NextFirings> firings = automationRuleService.nextScheduledFiringsByEnvironment(ids, now);

        List<com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO> rows = new java.util.ArrayList<>();
        for (com.tcgdigital.vmcontrol.model.Environment env : envs.values()) {
            String id = env.getEnvironmentId();
            com.tcgdigital.vmcontrol.model.AccessLevel level = levels.get(id);
            boolean groupsOnly = level == null;
            Map<LocalDate, BigDecimal> days = daily.getOrDefault(id, Map.of());
            BigDecimal mtd = null;
            BigDecimal change = null;
            List<BigDecimal> sparkline = List.of();
            if (!groupsOnly) {
                mtd = sumBetween(days, monthStart, today);
                BigDecimal previous = sumBetween(days, previousMonthStart,
                        previousMonthStart.withDayOfMonth(Math.min(today.getDayOfMonth(), previousMonthStart.lengthOfMonth())));
                change = previous.signum() == 0 ? null
                        : mtd.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP);
                List<BigDecimal> points = new java.util.ArrayList<>();
                for (int i = 0; i < SPARKLINE_DAYS; i++) {
                    points.add(money(days.getOrDefault(sparkStart.plusDays(i), BigDecimal.ZERO)));
                }
                sparkline = points;
                mtd = money(mtd);
            }
            VmRepository.EnvironmentVmCounts c = counts.get(id);
            AutomationRuleService.NextFirings next = firings.get(id);
            rows.add(new com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO(id, env.getName(), env.getDisplayName(),
                    groupsOnly ? "GROUPS" : "FULL", groupsOnly ? null : level.name(),
                    level == com.tcgdigital.vmcontrol.model.AccessLevel.ADMIN, mtd, change, sparkline,
                    c == null ? 0 : (int) c.getRunning(), c == null ? 0 : (int) c.getTotal(),
                    next == null ? 0 : next.ruleCount(), next == null ? null : next.nextStop(),
                    next == null ? null : next.nextStart(), null, groupsOnly ? GROUPS_HINT : null));
        }
        Comparator<com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO> byMtd = Comparator.comparing(
                r -> r.monthToDateEstimated() == null ? BigDecimal.valueOf(-1) : r.monthToDateEstimated(),
                Comparator.reverseOrder());
        rows.sort(Comparator.comparing((com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO r) -> !r.owner()).thenComparing(byMtd));
        int total = rows.size();
        boolean capped = globalAdmin && total > ADMIN_LIST_LIMIT;
        return new com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO.Response(
                capped ? rows.subList(0, ADMIN_LIST_LIMIT) : rows, capped, total, ADMIN_LIST_LIMIT);
    }

    private static BigDecimal sumBetween(Map<LocalDate, BigDecimal> days, LocalDate from, LocalDate to) {
        return days.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(to))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
