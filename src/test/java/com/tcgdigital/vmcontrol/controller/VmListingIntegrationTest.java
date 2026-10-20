package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** U05-T01/T02: the VM endpoints carry stateSince and instanceType. */
@AutoConfigureMockMvc
class VmListingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VmStateHistoryRepository historyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository inventoryRepository;

    private static final Instant SINCE = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(3, ChronoUnit.HOURS);

    private void transition(Vm vm, VmStatus status, Instant at) {
        VmStateHistory h = VmStateHistory.builder().historyId(UUID.randomUUID().toString()).vm(vm)
                .newStatus(status).changeSource("state_sync").build();
        historyRepository.saveAndFlush(h);
        jdbcTemplate.update("UPDATE vm_state_history SET created_at = ? WHERE history_id = ?", Timestamp.from(at), h.getHistoryId());
    }

    private record Fixture(Environment env, VmGroup group, Vm withHistory, Vm without) { }

    private Fixture fixture() {
        Environment env = newEnvironment("Listing");
        VmGroup group = newGroup(env, "web");
        Vm withHistory = newVm(group, "web-01", VmStatus.RUNNING);
        Vm without = newVm(group, "web-02", VmStatus.STOPPED);
        transition(withHistory, VmStatus.STOPPED, SINCE.minus(5, ChronoUnit.HOURS));
        transition(withHistory, VmStatus.RUNNING, SINCE);
        return new Fixture(env, group, withHistory, without);
    }

    private static String iso(Instant at) {
        return at.toString().replace("Z", "");
    }

    @Test
    void theEnvironmentListingCarriesStateSince() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(get("/api/v1/environments/" + f.env().getEnvironmentId() + "/vms").header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vms[?(@.vmId == '" + f.withHistory().getVmId() + "')].stateSince",
                        contains(startsWith(iso(SINCE).substring(0, 16)))))
                .andExpect(jsonPath("$[0].vms[?(@.vmId == '" + f.without().getVmId() + "')].stateSince",
                        contains(nullValue())));
    }

    @Test
    void theGroupPageCarriesStateSince() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(get("/api/v1/environments/" + f.env().getEnvironmentId() + "/vms/" + f.group().getGroupId() + "/page")
                        .header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.vmId == '" + f.withHistory().getVmId() + "')].stateSince",
                        contains(startsWith(iso(SINCE).substring(0, 16)))));
    }

    @Test
    void theListingCarriesTheInstanceTypeForEveryoneWhoCanView() throws Exception {
        Fixture f = fixture();
        com.tcgdigital.vmcontrol.model.VmInventorySnapshot snapshot = new com.tcgdigital.vmcontrol.model.VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(f.withHistory());
        snapshot.setProvider(com.tcgdigital.vmcontrol.model.CloudProvider.AWS);
        snapshot.setProviderVmId(f.withHistory().getProviderVmId());
        snapshot.setInstanceType("r6g.xlarge");
        snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
        inventoryRepository.saveAndFlush(snapshot);

        mockMvc.perform(get("/api/v1/environments/" + f.env().getEnvironmentId() + "/vms").header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vms[?(@.vmId == '" + f.withHistory().getVmId() + "')].instanceType", contains("r6g.xlarge")))
                .andExpect(jsonPath("$[0].vms[?(@.vmId == '" + f.without().getVmId() + "')].instanceType", contains(nullValue())));
    }

    @Test
    void oneVmCarriesStateSince() throws Exception {
        Fixture f = fixture();
        mockMvc.perform(get("/api/v1/environments/" + f.env().getEnvironmentId() + "/vms/" + f.withHistory().getVmId())
                        .header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stateSince", startsWith(iso(SINCE).substring(0, 16))));
        mockMvc.perform(get("/api/v1/environments/" + f.env().getEnvironmentId() + "/vms/" + f.without().getVmId())
                        .header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stateSince").value(nullValue()));
    }
}
