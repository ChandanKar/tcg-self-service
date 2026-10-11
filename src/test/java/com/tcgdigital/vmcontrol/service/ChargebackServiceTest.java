package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.ChargebackDTO;
import com.tcgdigital.vmcontrol.dto.ChargebackRowDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.CostSplitRule;
import com.tcgdigital.vmcontrol.model.CostSplitShare;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.CostSplitRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Monthly chargeback (E23-T06) over seeded daily snapshots: direct, split, Unassigned, coverage, totals. */
class ChargebackServiceTest extends AbstractIntegrationTest {

    private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

    @Autowired private ChargebackService chargebackService;
    @Autowired private CostDailySnapshotRepository snapshots;
    @Autowired private CostSplitRuleRepository rules;
    @Autowired private TeamRepository teams;
    @Autowired private EnvironmentRepository environments;

    private Team team(String name, String costCenter) {
        Team t = new Team();
        t.setTeamId(UUID.randomUUID().toString());
        t.setName(TeamService.slug(name));
        t.setDisplayName(name);
        t.setCostCenter(costCenter);
        return teams.saveAndFlush(t);
    }

    private Environment env(String name, Team team) {
        Environment e = newEnvironment(name);
        e.setTeam(team);
        return environments.saveAndFlush(e);
    }

    private void cost(Environment env, int day, String estimated, String actual) {
        CostDailySnapshot s = new CostDailySnapshot();
        s.setEnvironment(env);
        s.setSnapshotDate(Date.valueOf(MONTH.atDay(day)));
        s.setEstimatedCost(new BigDecimal(estimated));
        s.setActualCost(actual == null ? null : new BigDecimal(actual));
        snapshots.saveAndFlush(s);
    }

    private void rule(Environment env, CostSplitRule.Method method, YearMonth from, CostSplitShare... shares) {
        CostSplitRule r = new CostSplitRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setEnvironmentId(env.getEnvironmentId());
        r.setMethod(method);
        r.setEffectiveFrom(from.atDay(1));
        r.setShares(List.of(shares));
        rules.saveAndFlush(r);
    }

    private static ChargebackRowDTO row(ChargebackDTO c, String teamName) {
        return c.rows().stream().filter(r -> r.teamName().equals(teamName)).findFirst().orElseThrow();
    }

    @Test
    void directPlusAFiftyFiftySharedEnvironment() {
        Team x = team("Team X", "CC-X");
        Team y = team("Team Y", null);
        Environment a = env("Env A", x);
        Environment shared = env("Shared S", x);
        cost(a, 1, "100.00", null);
        cost(a, 2, "100.00", null);
        cost(a, 3, "100.00", null);
        cost(shared, 1, "60.00", null);
        cost(shared, 2, "40.00", null);
        rule(shared, CostSplitRule.Method.FIXED_PERCENT, MONTH,
                new CostSplitShare(x.getTeamId(), new BigDecimal("50.00")), new CostSplitShare(y.getTeamId(), new BigDecimal("50.00")));

        ChargebackDTO c = chargebackService.compute(MONTH);

        assertThat(c.month()).isEqualTo(MONTH.toString());
        assertThat(c.rows()).extracting(ChargebackRowDTO::teamName).containsExactly("Team X", "Team Y");
        ChargebackRowDTO rx = row(c, "Team X");
        assertThat(rx.directCost()).isEqualByComparingTo("300.00");
        assertThat(rx.sharedAllocated()).isEqualByComparingTo("50.00");
        assertThat(rx.total()).isEqualByComparingTo("350.00");
        assertThat(rx.costCenter()).isEqualTo("CC-X");
        assertThat(rx.environments()).extracting(ChargebackRowDTO.EnvironmentCharge::shared).containsExactly(false, true);
        assertThat(row(c, "Team Y").total()).isEqualByComparingTo("50.00");
        assertThat(c.totals().total()).isEqualByComparingTo("400.00");
        assertThat(c.totals().sharedAllocated()).isEqualByComparingTo("100.00");
        assertThat(c.dataSource()).startsWith("Estimates only");
    }

    @Test
    void actualsWinOverEstimatesAndCoverageIsReported() {
        Team x = team("Billing", null);
        Environment a = env("Billed", x);
        cost(a, 1, "10.00", "12.50");
        cost(a, 2, "10.00", "7.50");
        cost(a, 3, "10.00", null);
        cost(a, 4, "10.00", null);

        ChargebackDTO c = chargebackService.compute(MONTH);
        ChargebackRowDTO r = row(c, "Billing");
        assertThat(r.total()).isEqualByComparingTo("40.00");
        assertThat(r.actualCoveragePct()).isEqualByComparingTo("50.0");
        assertThat(c.dataSource()).isEqualTo("AWS billing for 50.0% of environment-days; estimates for the rest");
    }

    @Test
    void unownedEnvironmentsAreUnassignedAndRulesStartingLaterAreIgnored() {
        Team x = team("Owners", null);
        Team y = team("Others", null);
        Environment loose = env("Loose", null);
        Environment later = env("Later", x);
        cost(loose, 5, "20.00", null);
        cost(later, 5, "30.00", null);
        rule(later, CostSplitRule.Method.EVEN, MONTH.plusMonths(1),
                new CostSplitShare(x.getTeamId(), null), new CostSplitShare(y.getTeamId(), null));
        // Snapshots outside the month do not count.
        CostDailySnapshot outside = new CostDailySnapshot();
        outside.setEnvironment(loose);
        outside.setSnapshotDate(Date.valueOf(MONTH.plusMonths(1).atDay(1)));
        outside.setEstimatedCost(new BigDecimal("999.00"));
        snapshots.saveAndFlush(outside);

        ChargebackDTO c = chargebackService.compute(MONTH);
        assertThat(c.rows()).extracting(ChargebackRowDTO::teamName).containsExactly("Owners", "Unassigned");
        assertThat(row(c, "Owners").sharedAllocated()).isEqualByComparingTo("0.00");
        assertThat(row(c, "Unassigned").teamId()).isNull();
        assertThat(c.totals().total()).isEqualByComparingTo("50.00");
    }

    @Test
    void proportionalDirectUsesThisMonthsDirectTotals() {
        Team x = team("Heavy", null);
        Team y = team("Light", null);
        Environment ex = env("Heavy env", x);
        Environment ey = env("Light env", y);
        Environment ci = env("CI", null);
        cost(ex, 1, "300.00", null);
        cost(ey, 1, "100.00", null);
        cost(ci, 1, "40.00", null);
        rule(ci, CostSplitRule.Method.PROPORTIONAL_DIRECT, MONTH.minusMonths(3),
                new CostSplitShare(x.getTeamId(), null), new CostSplitShare(y.getTeamId(), null));

        ChargebackDTO c = chargebackService.compute(MONTH);
        assertThat(row(c, "Heavy").sharedAllocated()).isEqualByComparingTo("30.00");
        assertThat(row(c, "Light").sharedAllocated()).isEqualByComparingTo("10.00");
        assertThat(c.rows()).extracting(ChargebackRowDTO::teamName).doesNotContain("Unassigned");
        assertThat(c.totals().total()).isEqualByComparingTo("440.00");
    }

    @Test
    void anEmptyMonthAndBadMonths() {
        ChargebackDTO c = chargebackService.compute(MONTH);
        assertThat(c.rows()).isEmpty();
        assertThat(c.totals().total()).isEqualByComparingTo("0.00");
        assertThat(c.dataSource()).isEqualTo("No cost data for this month");

        assertThatThrownBy(() -> ChargebackService.parseMonth(YearMonth.now().plusMonths(1).toString()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ChargebackService.parseMonth("Sept")).isInstanceOf(ValidationException.class);
        assertThat(ChargebackService.parseMonth(YearMonth.now().toString())).isEqualTo(YearMonth.now());
    }

    @Test
    void csvNeutralisesFormulasAndEndsWithTotals() {
        Team evil = team("=HYPERLINK(1)", "+CC");
        cost(env("E", evil), 1, "5.00", null);

        String csv = new String(chargebackService.toCsv(chargebackService.compute(MONTH)), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("\"Team\",\"Cost center\",\"Direct cost\",\"Shared allocated\",\"Total\",\"Actual coverage %\"");
        assertThat(lines[1]).isEqualTo("\"'=HYPERLINK(1)\",\"'+CC\",5.00,0.00,5.00,0.0");
        assertThat(lines[2]).isEqualTo("\"Total\",\"\",5.00,0.00,5.00,0.0");
        assertThat(ChargebackService.csvCell(new BigDecimal("-3.00"))).isEqualTo("-3.00");
    }
}
