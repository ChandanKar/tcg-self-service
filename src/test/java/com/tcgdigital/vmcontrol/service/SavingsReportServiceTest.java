package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.SavingsBreakdownRowDTO;
import com.tcgdigital.vmcontrol.dto.SavingsSummaryDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.SavingsLedgerEntryRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Monthly savings totals, the export's totals block and the actuals check (E17-T06). */
class SavingsReportServiceTest extends AbstractIntegrationTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    @Autowired private SavingsReportService reports;
    @Autowired private SavingsLedgerEntryRepository ledger;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Vm vm;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Report");
        VmGroup group = newGroup(env, "app");
        vm = newVm(group, "web", VmStatus.STOPPED);
    }

    private void entry(LocalDate day, SavingsCause cause, String hours, String gross, String net) {
        SavingsLedgerEntry e = new SavingsLedgerEntry();
        e.setEntryId(UUID.randomUUID().toString());
        e.setLedgerDate(day);
        e.setVmId(vm.getVmId());
        e.setEnvironmentId(env.getEnvironmentId());
        e.setTeam("Payments");
        e.setCause(cause);
        e.setAvoidedHours(new BigDecimal(hours));
        e.setHourlyRate(new BigDecimal("0.10"));
        e.setGrossSaving(new BigDecimal(gross));
        e.setResidualStorageCost(BigDecimal.ZERO);
        e.setResidualIpv4Cost(BigDecimal.ZERO);
        e.setCommitmentAdjustment(BigDecimal.ZERO);
        e.setNetSaving(new BigDecimal(net));
        ledger.save(e);
    }

    @Test
    void environmentRowsCarryTheirTeam() {
        entry(MONTH.atDay(3), SavingsCause.SCHEDULE, "10", "1.00", "0.90");
        ledger.flush();

        // U15-T06: no team on the environment, so the team its ledger rows were written with.
        SavingsBreakdownRowDTO row = reports.byEnvironment(MONTH).get(0);
        assertThat(row.key()).isEqualTo(env.getEnvironmentId());
        assertThat(row.team()).isEqualTo("Payments");
        assertThat(reports.byTeam(MONTH).get(0).team()).isNull();
    }

    @Test
    void totalsByCauseEqualTheSumOfTheEntries() {
        entry(MONTH.atDay(1), SavingsCause.SCHEDULE, "14", "1.40", "1.30");
        entry(MONTH.atDay(2), SavingsCause.SCHEDULE, "14", "1.40", "1.30");
        entry(MONTH.atDay(2), SavingsCause.IDLE_STOP, "4", "0.40", "0.35");
        entry(MONTH.plusMonths(1).atDay(1), SavingsCause.SCHEDULE, "24", "2.40", "2.40"); // next month
        ledger.flush();

        SavingsSummaryDTO summary = reports.summary(MONTH, null);
        assertThat(summary.realisedNet()).isEqualByComparingTo("2.95");
        assertThat(summary.realisedGross()).isEqualByComparingTo("3.20");
        assertThat(summary.byCause()).extracting(SavingsBreakdownRowDTO::key).containsExactly("SCHEDULE", "IDLE_STOP");
        assertThat(reports.byTeam(MONTH)).singleElement().satisfies(r -> assertThat(r.net()).isEqualByComparingTo("2.95"));

        List<Object[]> rows = SavingsReportService.exportRows(ledger.findByLedgerDateBetweenOrderByLedgerDateAsc(
                MONTH.atDay(1), MONTH.atEndOfMonth()), Map.of(), Map.of());
        Object[] total = rows.get(rows.size() - 1);
        assertThat(total[4]).isEqualTo("Total");
        assertThat((BigDecimal) total[12]).isEqualByComparingTo("2.95");
        BigDecimal causeSum = rows.stream().filter(r -> r[4] != null && r[0] == null && r[12] != null && !"Total".equals(r[4]))
                .map(r -> (BigDecimal) r[12]).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(causeSum).isEqualByComparingTo("2.95");
        assertThat(reports.exportMonth(MONTH)).isNotEmpty();
        assertThat(reports.daily(MONTH, null)).hasSize(3)
                .allSatisfy(p -> assertThat(p.date().getMonthValue()).isEqualTo(9));
    }

    @Test
    void vmsOffWithoutThePlatformAreNotSavings() {
        // U22-T02 (staging review): $5,382 of a $5,400 'saved' was VMs that were simply off.
        entry(MONTH.atDay(1), SavingsCause.SCHEDULE, "14", "1.40", "1.30");
        entry(MONTH.atDay(1), SavingsCause.OTHER, "500", "50.00", "45.00");
        entry(MONTH.atDay(2), SavingsCause.AUTOMATION, "2", "0.20", "0.18");
        ledger.flush();

        SavingsSummaryDTO summary = reports.summary(MONTH, null);
        assertThat(summary.realisedNet()).isEqualByComparingTo("1.48");
        assertThat(summary.byCause()).extracting(SavingsBreakdownRowDTO::key).containsExactly("SCHEDULE", "AUTOMATION");
        assertThat(reports.daily(MONTH, null)).noneMatch(p -> "OTHER".equals(p.cause()));
        assertThat(reports.byTeam(MONTH)).singleElement().satisfies(r -> assertThat(r.net()).isEqualByComparingTo("1.48"));
    }

    @Test
    void anEmptyMonthIsZerosNotAnError() {
        SavingsSummaryDTO summary = reports.summary(YearMonth.of(2025, 1), env.getEnvironmentId());
        assertThat(summary.realisedNet()).isEqualByComparingTo("0");
        assertThat(summary.byCause()).isEmpty();
        assertThat(summary.actualsCheck().daysWithActuals()).isZero();
    }

    @Test
    void theActualsCheckOnlyUsesDaysThatHaveActuals() {
        for (int day = 1; day <= 30; day++) {
            jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost, "
                            + "actual_cost) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), env.getEnvironmentId(),
                    java.sql.Date.valueOf(MONTH.atDay(day)), new BigDecimal("11.00"),
                    day <= 20 ? new BigDecimal("10.00") : null);
        }
        SavingsSummaryDTO.ActualsCheck check = reports.actualsCheck(MONTH, env.getEnvironmentId());
        assertThat(check.daysWithActuals()).isEqualTo(20);
        assertThat(check.estimated()).isEqualByComparingTo("220.00");
        assertThat(check.actual()).isEqualByComparingTo("200.00");
        assertThat(check.variancePercent()).isEqualByComparingTo("10.0");
        assertThat(check.withinTarget()).isTrue();
    }
}
