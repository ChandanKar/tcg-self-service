package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EnvironmentCostDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.idle.IdleStopSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * An environment's cost for its users (E18-T01): month to date = complete days + today's live
 * estimate, actual coverage, the previous-month comparison, run-rate forecast and group scoping.
 * The clock is fixed on the 7th.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentCostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    @Mock private CostDailySnapshotRepository snapshots;
    @Mock private VmRepository vmRepository;
    @Mock private VmGroupRepository groupRepository;
    @Mock private CostDataProvider costDataProvider;
    @Mock private SecurityService securityService;
    @Mock private UserService userService;
    @Mock private AutomationRuleService automationRuleService;
    @Mock private IdleStopSummaryService idleStopSummaryService;
    @Mock private com.tcgdigital.vmcontrol.repository.EnvironmentRepository environmentRepository;
    @Mock private com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository accessRepository;

    private EnvironmentCostService service;
    private final User user = new User();
    private VmGroup web;
    private VmGroup db;
    private final List<Vm> vms = new ArrayList<>();
    private final List<CostDailySnapshot> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EnvironmentCostService(snapshots, vmRepository, groupRepository, costDataProvider, securityService,
                userService, automationRuleService, idleStopSummaryService, environmentRepository, accessRepository);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        web = group("g-web", env);
        db = group("g-db", env);
        vms.add(vm("web-1", web));
        vms.add(vm("db-1", db));
        when(vmRepository.findByEnvironmentIdFetchGroupAndEnvironment("env-1")).thenReturn(vms);
        when(groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc("env-1")).thenReturn(List.of(web, db));
        when(securityService.getVisibleGroupIds(user, "env-1")).thenReturn(List.of("g-web", "g-db"));
        when(automationRuleService.nextScheduledFirings(eq("env-1"), any()))
                .thenReturn(new AutomationRuleService.NextFirings(0, null, null));
        when(idleStopSummaryService.summarizeSince(eq("env-1"), any()))
                .thenReturn(new IdleStopSummaryService.Summary(0, BigDecimal.ZERO, 1, new BigDecimal("4.50"), List.of()));
        when(snapshots.findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(eq("env-1"), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate from = ((Date) inv.getArgument(1)).toLocalDate();
                    LocalDate to = ((Date) inv.getArgument(2)).toLocalDate();
                    return stored.stream().filter(s -> !s.getSnapshotDate().toLocalDate().isBefore(from)
                            && !s.getSnapshotDate().toLocalDate().isAfter(to)).toList();
                });
        // Today's partial (window starts at midnight): 3.00; the month window: web 50, db 20.
        when(costDataProvider.estimateCosts(anyList(), any(), any())).thenAnswer(inv -> {
            List<Vm> scope = inv.getArgument(0);
            Timestamp start = inv.getArgument(1);
            boolean today = start.toInstant().equals(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant());
            Map<String, CostDataProvider.VmCostEstimate> result = new HashMap<>();
            for (Vm vm : scope) {
                BigDecimal cost = today ? new BigDecimal("1.50") : vm.getVmId().equals("web-1") ? new BigDecimal("50") : new BigDecimal("20");
                result.put(vm.getVmId(), new CostDataProvider.VmCostEstimate(true, BigDecimal.ONE, BigDecimal.TEN, 0, cost));
            }
            return result;
        });
    }

    private static VmGroup group(String id, Environment env) {
        VmGroup g = new VmGroup();
        g.setGroupId(id);
        g.setEnvironment(env);
        return g;
    }

    private static Vm vm(String id, VmGroup group) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setName(id);
        vm.setGroup(group);
        vm.setStatus(VmStatus.RUNNING);
        vm.setProvider(CloudProvider.AWS);
        return vm;
    }

    private void snapshot(LocalDate day, String estimated, String actual) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setSnapshotDate(Date.valueOf(day));
        s.setEstimatedCost(new BigDecimal(estimated));
        s.setActualCost(actual == null ? null : new BigDecimal(actual));
        stored.add(s);
    }

    private void octoberAndSeptember() {
        for (int d = 1; d <= 6; d++) {
            snapshot(LocalDate.of(2026, 10, d), "10", d <= 3 ? "9" : null);
            snapshot(LocalDate.of(2026, 9, d), "8", null);
        }
    }

    @Test
    void monthToDateIsTheCompleteDaysPlusTodaysLiveEstimate() {
        octoberAndSeptember();

        EnvironmentCostDTO cost = service.getCost("env-1", user);

        assertThat(cost.scope()).isEqualTo("FULL");
        assertThat(cost.monthStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(cost.monthToDateEstimated()).isEqualByComparingTo("63.00"); // 6 x 10 + 2 x 1.50
        assertThat(cost.monthToDateActual()).isEqualByComparingTo("27.00");
        assertThat(cost.actualDays()).isEqualTo(3);
        assertThat(cost.previousMonthSameDays()).isEqualByComparingTo("48.00");
        assertThat(cost.changePercent()).isEqualByComparingTo("25.0");
        // Run rate: the last 7 complete days hold Oct 1-6 at 10/day; 24 days left.
        assertThat(cost.forecastMonthEnd()).isEqualByComparingTo("303.00");
        assertThat(cost.daily()).hasSize(6);           // Sept 1-6 are more than 30 days back
        assertThat(cost.savingsMtd()).isEqualByComparingTo("4.50");
        assertThat(cost.topVms()).extracting(EnvironmentCostDTO.TopVm::vmId).containsExactly("web-1", "db-1");
        assertThat(cost.leaseStatus()).isNull();
    }

    @Test
    void withoutLastMonthsDataTheComparisonIsNull() {
        snapshot(LocalDate.of(2026, 10, 1), "10", null);

        EnvironmentCostDTO cost = service.getCost("env-1", user);

        assertThat(cost.previousMonthSameDays()).isNull();
        assertThat(cost.changePercent()).isNull();
        assertThat(cost.monthToDateActual()).isNull();
        assertThat(cost.actualDays()).isZero();
    }

    @Test
    void aGroupOnlyGrantSeesItsGroupsWithoutEnvironmentLevelFigures() {
        octoberAndSeptember();
        when(securityService.getVisibleGroupIds(user, "env-1")).thenReturn(List.of("g-db"));

        EnvironmentCostDTO cost = service.getCost("env-1", user);

        assertThat(cost.scope()).isEqualTo("GROUPS");
        assertThat(cost.monthToDateEstimated()).isEqualByComparingTo("20.00"); // db-1 only
        assertThat(cost.topVms()).extracting(EnvironmentCostDTO.TopVm::vmId).containsExactly("db-1");
        assertThat(cost.monthToDateActual()).isNull();
        assertThat(cost.forecastMonthEnd()).isNull();
        assertThat(cost.previousMonthSameDays()).isNull();
        assertThat(cost.daily()).isEmpty();
        assertThat(cost.savingsMtd()).isNull();
    }

    @Test
    void theFirstOfTheMonthIsOnlyTodaysEstimate() {
        service.setClock(Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC));
        snapshot(LocalDate.of(2026, 9, 30), "10", null);
        org.mockito.Mockito.doAnswer(inv -> {
            Map<String, CostDataProvider.VmCostEstimate> result = new HashMap<>();
            for (Vm vm : (List<Vm>) inv.getArgument(0)) {
                result.put(vm.getVmId(), new CostDataProvider.VmCostEstimate(true, BigDecimal.ONE, BigDecimal.ONE, 0, new BigDecimal("2")));
            }
            return result;
        }).when(costDataProvider).estimateCosts(anyList(), any(), any());

        EnvironmentCostDTO cost = service.getCost("env-1", user);

        assertThat(cost.monthToDateEstimated()).isEqualByComparingTo("4.00");
        assertThat(cost.previousMonthSameDays()).isNull();
        // Sept 30 is within the last 7 days: 30 days left at 10/day.
        assertThat(cost.forecastMonthEnd()).isEqualByComparingTo("304.00");
    }

    // ---- E18-T02: the My Account cost list ----

    private static Environment environment(String id) {
        Environment e = new Environment();
        e.setEnvironmentId(id);
        e.setName(id);
        e.setDisplayName(id.toUpperCase());
        e.setIsActive(true);
        return e;
    }

    private static com.tcgdigital.vmcontrol.model.EnvironmentAccess grantOf(Environment env, User user,
            com.tcgdigital.vmcontrol.model.AccessLevel level, com.tcgdigital.vmcontrol.model.AccessScopeType scope) {
        com.tcgdigital.vmcontrol.model.EnvironmentAccess g =
                com.tcgdigital.vmcontrol.model.EnvironmentAccess.create(env, user, level, user);
        g.setScopeType(scope);
        return g;
    }

    private static CostDailySnapshotRepository.EnvironmentDailyCost row(String envId, LocalDate day, String cost) {
        return new CostDailySnapshotRepository.EnvironmentDailyCost() {
            public String getEnvironmentId() { return envId; }
            public Date getSnapshotDate() { return Date.valueOf(day); }
            public BigDecimal getEstimatedCost() { return new BigDecimal(cost); }
        };
    }

    @Test
    void theListMarksOwnersMasksGroupOnlyGrantsAndUsesOneSnapshotQuery() {
        user.setUserId("u-1");
        Environment a = environment("env-a");
        Environment b = environment("env-b");
        Environment c = environment("env-c");
        when(accessRepository.findActiveAccessByUser(eq("u-1"), any())).thenReturn(List.of(
                grantOf(a, user, com.tcgdigital.vmcontrol.model.AccessLevel.ADMIN, com.tcgdigital.vmcontrol.model.AccessScopeType.ENVIRONMENT),
                grantOf(b, user, com.tcgdigital.vmcontrol.model.AccessLevel.VIEWER, com.tcgdigital.vmcontrol.model.AccessScopeType.ENVIRONMENT),
                grantOf(c, user, com.tcgdigital.vmcontrol.model.AccessLevel.USER, com.tcgdigital.vmcontrol.model.AccessScopeType.GROUP)));
        List<CostDailySnapshotRepository.EnvironmentDailyCost> rows = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            rows.add(row("env-a", LocalDate.of(2026, 10, d), "10"));
            rows.add(row("env-b", LocalDate.of(2026, 10, d), "30"));
            rows.add(row("env-c", LocalDate.of(2026, 10, d), "99"));
            rows.add(row("env-a", LocalDate.of(2026, 9, d), "5"));
        }
        when(snapshots.findDailyByEnvironmentIdsBetween(anyList(), any(), any())).thenReturn(rows);
        when(vmRepository.countVmsGroupedByEnvironment(anyList(), any())).thenReturn(List.of());
        when(automationRuleService.nextScheduledFiringsByEnvironment(anyList(), any())).thenReturn(Map.of());

        var response = service.getMyEnvironmentsCost(user);

        assertThat(response.environments()).extracting(com.tcgdigital.vmcontrol.dto.MyEnvironmentCostDTO::environmentId)
                .containsExactly("env-a", "env-b", "env-c"); // owner first, then by month to date
        var ownerRow = response.environments().get(0);
        assertThat(ownerRow.owner()).isTrue();
        assertThat(ownerRow.monthToDateEstimated()).isEqualByComparingTo("60.00");
        assertThat(ownerRow.changePercent()).isEqualByComparingTo("100.0");          // 60 vs 30 (Sept 1-7)
        assertThat(ownerRow.sparkline()).hasSize(14);
        var viewerRow = response.environments().get(1);
        assertThat(viewerRow.owner()).isFalse();
        assertThat(viewerRow.myLevel()).isEqualTo("VIEWER");
        var groupRow = response.environments().get(2);
        assertThat(groupRow.scope()).isEqualTo("GROUPS");
        assertThat(groupRow.monthToDateEstimated()).isNull();
        assertThat(groupRow.sparkline()).isEmpty();
        assertThat(groupRow.hint()).contains("some groups only");
        org.mockito.Mockito.verify(snapshots, org.mockito.Mockito.times(1)).findDailyByEnvironmentIdsBetween(anyList(), any(), any());
        org.mockito.Mockito.verify(vmRepository, org.mockito.Mockito.times(1)).countVmsGroupedByEnvironment(anyList(), any());
    }

    @Test
    void globalAdminsSeeAllActiveEnvironmentsCappedAtFifty() {
        user.setAdmin(true);
        List<Environment> all = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            all.add(environment("env-" + i));
        }
        when(environmentRepository.findByIsActiveTrue()).thenReturn(all);
        when(snapshots.findDailyByEnvironmentIdsBetween(anyList(), any(), any())).thenReturn(List.of());
        when(vmRepository.countVmsGroupedByEnvironment(anyList(), any())).thenReturn(List.of());
        when(automationRuleService.nextScheduledFiringsByEnvironment(anyList(), any())).thenReturn(Map.of());

        var response = service.getMyEnvironmentsCost(user);

        assertThat(response.capped()).isTrue();
        assertThat(response.totalEnvironments()).isEqualTo(55);
        assertThat(response.environments()).hasSize(50).allSatisfy(r -> assertThat(r.owner()).isTrue());
        org.mockito.Mockito.verify(accessRepository, org.mockito.Mockito.never()).findActiveAccessByUser(any(), any());
    }

    @Test
    void noEnvironmentsMeansAnEmptyListWithoutQueries() {
        user.setUserId("u-2");
        when(accessRepository.findActiveAccessByUser(eq("u-2"), any())).thenReturn(List.of());

        assertThat(service.getMyEnvironmentsCost(user).environments()).isEmpty();
        org.mockito.Mockito.verify(snapshots, org.mockito.Mockito.never()).findDailyByEnvironmentIdsBetween(anyList(), any(), any());
    }
}
