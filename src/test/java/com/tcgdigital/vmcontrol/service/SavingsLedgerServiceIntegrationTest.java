package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationTriggerSource;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.SavingsCause;
import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.SavingsLedgerEntryRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One day of the savings ledger end to end (E17-T03). */
class SavingsLedgerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SavingsLedgerService ledgerService;
    @Autowired private SavingsLedgerEntryRepository ledger;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private VmInventorySnapshotRepository inventory;
    @Autowired private PricingReferenceService pricing;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private User user;
    private Environment env;
    private VmGroup group;

    @BeforeEach
    void setUp() {
        user = newUser("ledger@example.org", false, false);
        env = newEnvironment("Ledger");
        group = newGroup(env, "app");
    }

    private Vm pricedVm(String name, VmStatus status) {
        Vm vm = newVm(group, name, status);
        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(vm);
        snapshot.setProvider(CloudProvider.AWS);
        snapshot.setProviderVmId(vm.getProviderVmId());
        snapshot.setInstanceType("t3.medium");
        snapshot.setLastRefreshedAt(Timestamp.from(java.time.Instant.now()));
        inventory.saveAndFlush(snapshot);
        return vm;
    }

    private String operation(OperationTriggerSource source) {
        OperationExecution e = new OperationExecution();
        e.setExecutionId(UUID.randomUUID().toString());
        e.setEnvironment(env);
        e.setOperationType(OperationType.STOP);
        e.setInitiatedByUserId(user.getUserId());
        e.setTriggerSource(source);
        return executions.saveAndFlush(e).getExecutionId();
    }

    private void history(Vm vm, VmStatus from, VmStatus to, LocalDate day, int hour, String operationId) {
        jdbcTemplate.update("INSERT INTO vm_state_history (history_id, vm_id, previous_status, new_status, change_source, "
                        + "created_at, operation_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), vm.getVmId(), from == null ? null : from.name(), to.name(),
                operationId == null ? "state_sync" : "operation",
                Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(hour * 3600L)), operationId);
    }

    private BigDecimal rate() {
        return pricing.lookupHourlyRate("AWS", "t3.medium", "ap-south-1").hourlyRate();
    }

    @Test
    void anOvernightScheduledStopCreditsTheHoursInsideTheDay() {
        // Stopped by a schedule at 19:00 the day before, started at 09:00, stopped again at 19:00.
        Vm vm = pricedVm("web", VmStatus.STOPPED);
        String stop = operation(OperationTriggerSource.SCHEDULE);
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY.minusDays(1), 19, stop);
        history(vm, VmStatus.STOPPED, VmStatus.RUNNING, DAY, 9, operation(OperationTriggerSource.SCHEDULE));
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY, 19, operation(OperationTriggerSource.SCHEDULE));

        assertThat(ledgerService.captureForDate(DAY)).isEqualTo(1);
        List<SavingsLedgerEntry> rows = ledger.findByLedgerDateBetweenOrderByLedgerDateAsc(DAY, DAY);
        assertThat(rows).hasSize(1);
        SavingsLedgerEntry row = rows.get(0);
        assertThat(row.getCause()).isEqualTo(SavingsCause.SCHEDULE);
        assertThat(row.getAvoidedHours()).isEqualByComparingTo("14"); // 00-09 and 19-24
        assertThat(row.getGrossSaving()).isEqualByComparingTo(rate().multiply(new BigDecimal("14")).setScale(2,
                java.math.RoundingMode.HALF_UP));
        assertThat(row.getCommitmentCoveragePercent()).isNull();
        assertThat(row.getCommitmentAdjustment()).isEqualByComparingTo("0");
        assertThat(row.isPriceApproximate()).isEqualTo(pricing.lookupHourlyRate("AWS", "t3.medium", "ap-south-1").approximate());
    }

    @Test
    void causesComeFromTheOperationsAndStateSyncStopsAreOther() {
        Vm vm = pricedVm("api", VmStatus.RUNNING);
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY, 2, operation(OperationTriggerSource.IDLE_STOP));
        history(vm, VmStatus.STOPPED, VmStatus.RUNNING, DAY, 6, null);
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY, 20, null); // stopped outside the app

        ledgerService.captureForDate(DAY);
        List<SavingsLedgerEntry> rows = ledger.findByLedgerDateBetweenOrderByLedgerDateAsc(DAY, DAY);
        assertThat(rows).extracting(SavingsLedgerEntry::getCause)
                .containsExactlyInAnyOrder(SavingsCause.IDLE_STOP, SavingsCause.OTHER);
        assertThat(rows).filteredOn(r -> r.getCause() == SavingsCause.IDLE_STOP).first()
                .extracting(SavingsLedgerEntry::getAvoidedHours).satisfies(h ->
                        assertThat((BigDecimal) h).isEqualByComparingTo("4"));
    }

    @Test
    void reRunningADayRewritesItAndUnpricedOrEksVmsAreSkipped() {
        Vm vm = pricedVm("db", VmStatus.STOPPED);
        newVm(group, "no-price", VmStatus.STOPPED); // no inventory: unknown instance type
        Vm eks = newVm(group, "nodes", VmStatus.STOPPED);
        eks.setProvider(CloudProvider.AWS_EKS);
        vmRepository.saveAndFlush(eks);
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY.minusDays(2), 12, operation(OperationTriggerSource.MANUAL));

        assertThat(ledgerService.captureForDate(DAY)).isEqualTo(1);
        assertThat(ledgerService.captureForDate(DAY)).isEqualTo(1);
        List<SavingsLedgerEntry> rows = ledger.findByLedgerDateBetweenOrderByLedgerDateAsc(DAY, DAY);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVmId()).isEqualTo(vm.getVmId());
        assertThat(rows.get(0).getCause()).isEqualTo(SavingsCause.MANUAL_STOP);
        assertThat(rows.get(0).getAvoidedHours()).isEqualByComparingTo("24");
    }

    @Test
    void commitmentCoverageReducesTheNetSaving() {
        Vm vm = pricedVm("cache", VmStatus.STOPPED);
        history(vm, VmStatus.RUNNING, VmStatus.STOPPED, DAY.minusDays(1), 12, operation(OperationTriggerSource.SCHEDULE));
        jdbcTemplate.update("INSERT INTO reservation_coverage_snapshot (snapshot_id, snapshot_date, coverage_percent, "
                + "sp_coverage_percent) VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), java.sql.Date.valueOf(DAY),
                new BigDecimal("30"), new BigDecimal("100"));

        ledgerService.captureForDate(DAY);
        SavingsLedgerEntry row = ledger.findByLedgerDateBetweenOrderByLedgerDateAsc(DAY, DAY).get(0);
        assertThat(row.getCommitmentCoveragePercent()).isEqualByComparingTo("100");
        assertThat(row.getNetSaving()).isEqualByComparingTo("0");
        assertThat(row.getNote()).contains("Savings Plans");
    }
}
