package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationTriggerSource;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Every operation records what started it, and the state-history rows it writes carry its id
 * (E17-T01). Not @Transactional: operations run after commit.
 */
class StopCauseTrackingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operations;
    @Autowired private StateSyncService stateSyncService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private Environment env;
    private Vm vm;

    @BeforeEach
    void setUp() {
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)));
        user = newUser("cause@example.org", false, false);
        env = newEnvironment("Causes");
        VmGroup group = newGroup(env, "web");
        vm = newVm(group, "app", VmStatus.RUNNING);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
    }

    private StartOperationDTO stopAll() {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setSkipAlreadyInTargetState(false);
        return dto;
    }

    private void awaitDone(String executionId) {
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT status FROM operation_execution WHERE "
                + "execution_id = ?", String.class, executionId)).isIn("completed", "partial_success", "failed"));
    }

    @Test
    void aScheduledStopRecordsItsSourceAndTheHistoryCarriesTheOperation() {
        OperationExecution execution = operations.startOperation(env.getEnvironmentId(), user.getUserId(), stopAll(),
                OperationTriggerSource.SCHEDULE);
        awaitDone(execution.getExecutionId());

        assertThat(executions.findById(execution.getExecutionId()).orElseThrow().getTriggerSource())
                .isEqualTo(OperationTriggerSource.SCHEDULE);
        List<String> operationIds = jdbcTemplate.queryForList("SELECT operation_id FROM vm_state_history WHERE vm_id = ? "
                + "AND new_status = 'STOPPED'", String.class, vm.getVmId());
        assertThat(operationIds).containsExactly(execution.getExecutionId());
    }

    @Test
    void aUserStopIsManual() {
        OperationExecution execution = operations.startOperation(env.getEnvironmentId(), user.getUserId(), stopAll());
        awaitDone(execution.getExecutionId());
        assertThat(executions.findById(execution.getExecutionId()).orElseThrow().getTriggerSource())
                .isEqualTo(OperationTriggerSource.MANUAL);
        assertThat(jdbcTemplate.queryForObject("SELECT trigger_source FROM operation_execution WHERE execution_id = ?",
                String.class, execution.getExecutionId())).isEqualTo("MANUAL");
    }

    @Test
    void aStateChangeWithoutAnOperationKeepsTheColumnEmpty() {
        stateSyncService.recordStateChange(vm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift");
        stateSyncService.recordStateChange(vm, VmStatus.STOPPED, VmStatus.RUNNING, "operation", user.getUserId(),
                "op-123", "manual");
        assertThat(jdbcTemplate.queryForList("SELECT operation_id FROM vm_state_history WHERE vm_id = ? "
                + "ORDER BY created_at, history_id", String.class, vm.getVmId())).containsExactlyInAnyOrder(null, "op-123");
    }

    @Test
    void automationRulesMapToScheduleOrAccessAutomation() {
        AutomationRule schedule = new AutomationRule();
        schedule.setTriggerType(AutomationTriggerType.SCHEDULE);
        AutomationRule access = new AutomationRule();
        access.setTriggerType(AutomationTriggerType.ACCESS_GRANT);
        assertThat(AutomationRuleService.triggerSourceFor(schedule)).isEqualTo(OperationTriggerSource.SCHEDULE);
        assertThat(AutomationRuleService.triggerSourceFor(access)).isEqualTo(OperationTriggerSource.ACCESS_AUTOMATION);
    }
}
