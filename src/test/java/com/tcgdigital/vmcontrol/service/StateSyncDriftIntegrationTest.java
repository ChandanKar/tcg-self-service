package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The scheduled state sync end to end (E09-T05, H23, M5): it runs without a session on worker
 * threads, so drift must be recorded with the environment loaded up front; a user's change made
 * while sync was fetching wins; a no-drift pass leaves updated_at alone.
 */
class StateSyncDriftIntegrationTest extends AbstractIntegrationTest {

    @Autowired private StateSyncService stateSyncService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Vm vm;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Sync");
        vm = newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        User member = newUser("sync-member-" + System.nanoTime() + "@example.com", false, false);
        grant(member, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
    }

    /** AWS reports {@code status} for this test's VM (others stay unknown and are skipped). */
    private void awsReports(VmStatus status, Consumer<String> whileFetching) {
        when(awsCloudProviderService.getVmStatusBatch(anyList(), anyString())).thenAnswer(inv -> {
            whileFetching.accept(vm.getVmId());
            Map<String, VmStatus> result = new HashMap<>();
            for (Object id : (List<?>) inv.getArgument(0)) {
                if (id.equals(vm.getProviderVmId())) {
                    result.put(vm.getProviderVmId(), status);
                }
            }
            return result.isEmpty() ? Map.of("i-none", VmStatus.UNKNOWN) : result;
        });
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void consoleStopIsRecordedAsDriftWithItsEnvironment() {
        awsReports(VmStatus.STOPPED, id -> { });

        stateSyncService.syncAllVmStates();

        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'STATE_DRIFT_DETECTED' " +
                "AND target_id = ? AND environment_id = ?", vm.getVmId(), env.getEnvironmentId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE entity_id = ? AND type = 'STATE_DRIFT_DETECTED'",
                vm.getVmId())).isPositive();
        assertThat(count("SELECT COUNT(*) FROM vm_state_history WHERE vm_id = ? AND change_source = 'state_sync'",
                vm.getVmId())).isEqualTo(1);
    }

    @Test
    void aUserStartDuringTheFetchWinsOverSync() {
        jdbcTemplate.update("UPDATE vm SET status = 'STOPPED' WHERE vm_id = ?", vm.getVmId());
        // While sync is talking to AWS, a user starts the VM.
        awsReports(VmStatus.RUNNING, id ->
                jdbcTemplate.update("UPDATE vm SET status = 'STARTING' WHERE vm_id = ?", id));

        stateSyncService.syncAllVmStates();

        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getStatus()).isEqualTo(VmStatus.STARTING);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'STATE_DRIFT_DETECTED' AND target_id = ?",
                vm.getVmId())).isZero();
    }

    @Test
    void noDriftRecordsTheSyncTimeButNotUpdatedAt() {
        Timestamp longAgo = Timestamp.valueOf("2026-01-01 00:00:00");
        jdbcTemplate.update("UPDATE vm SET updated_at = ?, state_drift_detected = TRUE, last_state_sync_at = NULL " +
                "WHERE vm_id = ?", longAgo, vm.getVmId());
        awsReports(VmStatus.RUNNING, id -> { });

        stateSyncService.syncAllVmStates();

        Vm after = vmRepository.findById(vm.getVmId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(after.getStateDriftDetected()).isFalse();
        assertThat(after.getLastStateSyncAt()).isNotNull();
        assertThat(after.getUpdatedAt()).isEqualTo(longAgo);
    }

    @Test
    void aRenameDuringSyncIsNotOverwrittenByTheCloudName() {
        jdbcTemplate.update("UPDATE vm SET name = ?, display_name = ? WHERE vm_id = ?",
                vm.getProviderVmId(), vm.getProviderVmId(), vm.getVmId());
        // The admin renames the VM while sync is fetching its Name tag.
        when(awsCloudProviderService.getVmName(anyString(), anyString())).thenAnswer(inv -> {
            jdbcTemplate.update("UPDATE vm SET name = 'renamed-by-admin', display_name = 'Renamed' WHERE vm_id = ?",
                    vm.getVmId());
            return "web-01";
        });
        awsReports(VmStatus.RUNNING, id -> { });

        stateSyncService.syncAllVmStates();

        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getName()).isEqualTo("renamed-by-admin");
    }
}
