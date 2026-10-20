package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U05-T01/T02: when each listed VM entered its current status (state history) and its instance type
 * (inventory snapshot), against MySQL.
 */
class VmListDetailsServiceTest extends AbstractIntegrationTest {

    @Autowired private VmListDetailsService detailsService;
    @Autowired private VmStateHistoryRepository historyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VmInventorySnapshotRepository inventoryRepository;

    private void snapshot(Vm vm, CloudProvider provider, String instanceType, String privateIp) {
        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(vm);
        snapshot.setProvider(provider);
        snapshot.setProviderVmId(vm.getProviderVmId());
        snapshot.setInstanceType(instanceType);
        snapshot.setPrivateIp(privateIp);
        snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
        inventoryRepository.saveAndFlush(snapshot);
    }

    private static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);

    private void transition(Vm vm, VmStatus status, Instant at) {
        VmStateHistory h = VmStateHistory.builder().historyId(UUID.randomUUID().toString()).vm(vm)
                .newStatus(status).changeSource("state_sync").build();
        historyRepository.saveAndFlush(h);
        // created_at is a @CreationTimestamp: move it afterwards.
        jdbcTemplate.update("UPDATE vm_state_history SET created_at = ? WHERE history_id = ?", Timestamp.from(at), h.getHistoryId());
    }

    private static Instant at(int hour, int minute) {
        return DAY.plus(hour, ChronoUnit.HOURS).plus(minute, ChronoUnit.MINUTES);
    }

    @Test
    void theLatestEntryIntoTheCurrentStatusWins() {
        Vm web = newVm(newGroup(newEnvironment("Since"), "web"), "web-01", VmStatus.RUNNING);
        transition(web, VmStatus.RUNNING, at(8, 5));
        transition(web, VmStatus.STOPPED, at(12, 0));
        transition(web, VmStatus.RUNNING, at(14, 10));

        VmListDetailsService.Details details = detailsService.detailsFor(List.of(web)).get(web.getVmId());

        assertThat(details.stateSince().toInstant()).isEqualTo(at(14, 10));
    }

    @Test
    void withoutAHistoryRowForTheCurrentStatusItIsNull() {
        VmGroup group = newGroup(newEnvironment("Since none"), "db");
        Vm never = newVm(group, "db-01", VmStatus.RUNNING);
        Vm otherStatusOnly = newVm(group, "db-02", VmStatus.RUNNING);
        transition(otherStatusOnly, VmStatus.STOPPED, at(9, 0));

        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(List.of(never, otherStatusOnly));

        assertThat(details.get(never.getVmId()).stateSince()).isNull();
        assertThat(details.get(otherStatusOnly.getVmId()).stateSince()).isNull();
    }

    @Test
    void severalVmsInOneCallEachGetTheirOwnTime() {
        Environment env = newEnvironment("Since many");
        VmGroup group = newGroup(env, "app");
        Vm running = newVm(group, "app-01", VmStatus.RUNNING);
        Vm stopped = newVm(group, "app-02", VmStatus.STOPPED);
        transition(running, VmStatus.RUNNING, at(7, 0));
        transition(stopped, VmStatus.RUNNING, at(7, 30));
        transition(stopped, VmStatus.STOPPED, at(19, 45));

        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(List.of(running, stopped));

        assertThat(details.get(running.getVmId()).stateSince().toInstant()).isEqualTo(at(7, 0));
        assertThat(details.get(stopped.getVmId()).stateSince().toInstant()).isEqualTo(at(19, 45));
    }

    @Test
    void theInstanceTypeComesFromTheInventorySnapshot() {
        VmGroup group = newGroup(newEnvironment("Sizes"), "data");
        Vm ec2 = newVm(group, "api-01", VmStatus.RUNNING);
        Vm rds = newVm(group, "orders-db", VmStatus.RUNNING);
        Vm unread = newVm(group, "new-01", VmStatus.STOPPED);
        snapshot(ec2, CloudProvider.AWS, "t3.medium", "10.0.1.15");
        snapshot(rds, CloudProvider.AWS, "db.t3.medium", null);

        Map<String, VmListDetailsService.Details> details = detailsService.detailsFor(List.of(ec2, rds, unread));

        assertThat(details.get(ec2.getVmId()).instanceType()).isEqualTo("t3.medium");
        assertThat(details.get(ec2.getVmId()).privateIp()).isEqualTo("10.0.1.15");
        assertThat(details.get(rds.getVmId()).instanceType()).isEqualTo("db.t3.medium");
        assertThat(details.get(unread.getVmId()).instanceType()).isNull();
        assertThat(details.get(unread.getVmId()).privateIp()).isNull();
    }

    @Test
    void anEmptyListAsksNothing() {
        assertThat(detailsService.detailsFor(List.of())).isEmpty();
    }
}
