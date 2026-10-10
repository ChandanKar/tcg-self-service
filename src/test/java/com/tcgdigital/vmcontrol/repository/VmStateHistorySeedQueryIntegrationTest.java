package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batched seed lookup returns each VM's latest transition before a moment, in one query
 * (E08-T04). Against MySQL.
 */
class VmStateHistorySeedQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmStateHistoryRepository historyRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void transition(Vm vm, VmStatus status, Instant at) {
        VmStateHistory h = VmStateHistory.builder().historyId(UUID.randomUUID().toString()).vm(vm)
                .newStatus(status).changeSource("system").build();
        historyRepository.saveAndFlush(h);
        // created_at is a @CreationTimestamp: move it afterwards.
        jdbcTemplate.update("UPDATE vm_state_history SET created_at = ? WHERE history_id = ?", Timestamp.from(at), h.getHistoryId());
    }

    @Test
    void eachVmGetsItsLatestTransitionBeforeTheMoment() {
        Environment env = newEnvironment("Seed");
        Vm a = newVm(newGroup(env, "a"), "a", VmStatus.STOPPED);
        Vm b = newVm(newGroup(env, "b"), "b", VmStatus.STOPPED);
        Vm none = newVm(newGroup(env, "c"), "c", VmStatus.STOPPED);
        Instant before = Instant.now().minus(Duration.ofDays(1));
        transition(a, VmStatus.STOPPED, before.minus(Duration.ofHours(5)));
        transition(a, VmStatus.RUNNING, before.minus(Duration.ofHours(1)));
        transition(a, VmStatus.STOPPED, before.plus(Duration.ofHours(1))); // after the moment
        transition(b, VmStatus.STOPPED, before.minus(Duration.ofHours(2)));

        List<VmStateHistory> rows = historyRepository.findLatestBeforeForVms(
                List.of(a.getVmId(), b.getVmId(), none.getVmId()), Timestamp.from(before));

        Map<String, VmStatus> byVm = rows.stream().collect(Collectors.toMap(h -> h.getVm().getVmId(), VmStateHistory::getNewStatus));
        assertThat(byVm).containsExactlyInAnyOrderEntriesOf(Map.of(a.getVmId(), VmStatus.RUNNING, b.getVmId(), VmStatus.STOPPED));
    }
}
