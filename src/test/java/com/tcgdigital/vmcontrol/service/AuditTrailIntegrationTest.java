package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Edits and per-VM operation results are audited (E11-T03, M31): group, VM and environment
 * edits record their actor and changed fields; each VM in a start/stop gets a completed or
 * failed row, which the VM-operations report returns. Against MySQL; not @Transactional.
 */
class AuditTrailIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmGroupService groupService;
    @Autowired private EnvironmentService environmentService;
    @Autowired private VmOperationsService operationsService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User actor;
    private Environment env;

    @BeforeEach
    void setUp() {
        actor = newUser("audit-trail-" + UUID.randomUUID() + "@example.com", true, false);
        env = newEnvironment("Trail");
    }

    private String latestDetails(String action, String targetId) {
        return jdbcTemplate.queryForList("SELECT details FROM audit_log WHERE action_type = ? AND target_id = ? "
                + "AND user_id = ? ORDER BY created_at DESC", String.class, action, targetId, actor.getUserId())
                .stream().findFirst().orElse(null);
    }

    @Test
    void aGroupEditRecordsItsActorAndChangedFields() {
        CreateVmGroupDTO create = new CreateVmGroupDTO();
        create.setName("web");
        create.setDisplayName("Web");
        create.setSequencePosition(1);
        VmGroup group = groupService.createGroup(env.getEnvironmentId(), create, actor.getUserId());

        CreateVmGroupDTO edit = new CreateVmGroupDTO();
        edit.setName("web");
        edit.setDisplayName("Web tier");
        edit.setSequencePosition(1);
        groupService.updateGroup(group.getGroupId(), edit, actor.getUserId());

        awaitAsync(() -> {
            assertThat(latestDetails("GROUP_CREATED", group.getGroupId())).isNotNull();
            assertThat(latestDetails("GROUP_UPDATED", group.getGroupId())).contains("displayName: Web -> Web tier");
        });
    }

    @Test
    void anEnvironmentRenameRecordsOldAndNewNames() {
        UpdateEnvironmentDTO dto = new UpdateEnvironmentDTO();
        dto.setDisplayName("Renamed Trail");

        environmentService.updateEnvironment(env.getEnvironmentId(), dto, actor.getUserId());

        awaitAsync(() -> assertThat(latestDetails("ENVIRONMENT_UPDATED", env.getEnvironmentId()))
                .contains("displayName: " + env.getDisplayName() + " -> Renamed Trail"));
    }

    private static final Set<ExecutionStatus> FINAL = Set.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
            ExecutionStatus.PARTIAL_SUCCESS, ExecutionStatus.CANCELLED);

    private void awaitFinal(String executionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            OperationExecution e = executions.findById(executionId).orElseThrow();
            if (FINAL.contains(e.getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Execution did not finish");
    }

    private int rows(String action, String vmId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = ? AND target_id = ?",
                Integer.class, action, vmId);
        return n == null ? 0 : n;
    }

    @Test
    void eachVmInAStartGetsACompletedRowAndTheReportReturnsThem() throws Exception {
        VmGroup group = newGroup(env, "app");
        Vm a = newVm(group, "a", VmStatus.STOPPED);
        Vm b = newVm(group, "b", VmStatus.STOPPED);
        User user = newUser("audit-ops-" + UUID.randomUUID() + "@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(
                CloudProviderService.VmOperationResult.success("req-1", VmStatus.RUNNING)));
        Timestamp before = Timestamp.from(Instant.now().minusSeconds(5));

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);
        awaitFinal(operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto).getExecutionId());

        awaitAsync(() -> {
            assertThat(rows("VM_START_COMPLETED", a.getVmId())).isEqualTo(1);
            assertThat(rows("VM_START_COMPLETED", b.getVmId())).isEqualTo(1);
            assertThat(auditLogs.findVmOperationsInRange(before, Timestamp.from(Instant.now().plusSeconds(5))))
                    .extracting(l -> l.getTargetId()).contains(a.getVmId(), b.getVmId());
        });
    }

    @Test
    void aFailedStopGetsAFailedRowWithTheError() throws Exception {
        VmGroup group = newGroup(env, "app");
        Vm vm = newVm(group, "c", VmStatus.RUNNING);
        User user = newUser("audit-stop-" + UUID.randomUUID() + "@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);
        when(awsCloudProviderService.stopVm(eq(vm.getProviderVmId()), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.failure("throttled")));

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setSkipAlreadyInTargetState(false);
        awaitFinal(operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto).getExecutionId());

        awaitAsync(() -> assertThat(jdbcTemplate.queryForList("SELECT action_status FROM audit_log WHERE action_type = "
                + "'VM_STOP_FAILED' AND target_id = ?", String.class, vm.getVmId())).containsExactly("failed"));
    }
}
