package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
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
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operations stay inside the path environment and only the right people cancel them
 * (E05-T01, H1, H11).
 */
class OperationScopeSecurityTest extends SecuredWebTestBase {

    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private AuditLogRepository auditLogs;

    private Environment envA;
    private Environment envB;
    private VmGroup groupB;
    private Vm vmB;
    private User otherUser;

    @BeforeEach
    void setUpTwoEnvironments() {
        envA = newEnvironment("Ops A");
        envB = newEnvironment("Ops B");
        newVm(newGroup(envA, "app-a"), "vm-a", VmStatus.RUNNING);
        groupB = newGroup(envB, "app-b");
        vmB = newVm(groupB, "vm-b", VmStatus.RUNNING);
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.USER);
        grantEnv(operator, envB.getEnvironmentId(), AccessLevel.USER);
        otherUser = newUser("other-operator@example.com", false, false);
        grantEnv(otherUser, envA.getEnvironmentId(), AccessLevel.USER);
    }

    private String opsUrl(Environment env) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/operations";
    }

    private long executionsIn(Environment env) {
        return executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()).size();
    }

    /** An in-progress execution started by {@code initiator}, inserted directly (no async run). */
    private OperationExecution runningExecution(Environment env, User initiator) {
        OperationExecution execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(env);
        execution.setOperationType(OperationType.STOP);
        execution.setStatus(ExecutionStatus.IN_PROGRESS);
        execution.setInitiatedByUserId(initiator.getUserId());
        execution.setStartedAt(new Timestamp(System.currentTimeMillis()));
        execution.setTotalTargets(1);
        return executions.saveAndFlush(execution);
    }

    private ExecutionStatus statusOf(OperationExecution execution) {
        return executions.findById(execution.getExecutionId()).orElseThrow().getStatus();
    }

    // ---------------------------------------------------------------- targets (H1)

    @Test
    void groupsOfAnotherEnvironmentAre404AndNothingStarts() throws Exception {
        lockService.acquireLock(envA.getEnvironmentId(), operator.getUserId(), "release", null);

        expectError(mockMvc.perform(post(opsUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationType\":\"STOP\",\"groupIds\":[\"" + groupB.getGroupId() + "\"]}")), 404);

        assertThat(executionsIn(envA)).isZero();
        assertThat(executionsIn(envB)).isZero();
    }

    @Test
    void vmsOfAnotherEnvironmentAre404AndNothingStarts() throws Exception {
        lockService.acquireLock(envA.getEnvironmentId(), operator.getUserId(), "release", null);

        expectError(mockMvc.perform(post(opsUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationType\":\"STOP\",\"vmIds\":[\"" + vmB.getVmId() + "\"]}")), 404);

        assertThat(executionsIn(envA)).isZero();
        assertThat(executionsIn(envB)).isZero();
    }

    @Test
    void unknownVmIdIs404() throws Exception {
        lockService.acquireLock(envA.getEnvironmentId(), operator.getUserId(), "release", null);

        expectError(mockMvc.perform(post(opsUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationType\":\"STOP\",\"vmIds\":[\"no-such-vm\"]}")), 404);
    }

    @Test
    void estimatesForAVmOrGroupOfAnotherEnvironmentAre404() throws Exception {
        expectError(mockMvc.perform(get(opsUrl(envA) + "/time-estimates").param("vmId", vmB.getVmId())
                .with(asUser())), 404);
        expectError(mockMvc.perform(get(opsUrl(envA) + "/time-estimates").param("groupId", groupB.getGroupId())
                .with(asUser())), 404);
        mockMvc.perform(get(opsUrl(envB) + "/time-estimates").param("vmId", vmB.getVmId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeLevel").value("VM"));
    }

    // ---------------------------------------------------------------- executions (H11)

    @Test
    void anExecutionOfAnotherEnvironmentIs404ForGetAndCancel() throws Exception {
        OperationExecution inB = runningExecution(envB, operator);

        expectError(mockMvc.perform(get(opsUrl(envA) + "/" + inB.getExecutionId()).with(asUser())), 404);
        expectError(mockMvc.perform(post(opsUrl(envA) + "/" + inB.getExecutionId() + "/cancel").with(asUser())), 404);

        assertThat(statusOf(inB)).isEqualTo(ExecutionStatus.IN_PROGRESS);
    }

    @Test
    void anotherUserWithoutTheLockCannotCancel() throws Exception {
        OperationExecution execution = runningExecution(envA, operator);

        expectError(mockMvc.perform(post(opsUrl(envA) + "/" + execution.getExecutionId() + "/cancel")
                .with(as(otherUser))), 403);

        assertThat(statusOf(execution)).isEqualTo(ExecutionStatus.IN_PROGRESS);
    }

    @Test
    void theInitiatorCancelsAndTheCancelIsAudited() throws Exception {
        OperationExecution execution = runningExecution(envA, operator);

        mockMvc.perform(post(opsUrl(envA) + "/" + execution.getExecutionId() + "/cancel").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf(execution)).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("OPERATION_CANCELLED", PageRequest.of(0, 10)).getContent())
                .anySatisfy(a -> {
                    assertThat(a.getTargetId()).isEqualTo(execution.getExecutionId());
                    assertThat(a.getUserId()).isEqualTo(operator.getUserId());
                });
    }

    @Test
    void theLockHolderCanCancelSomeoneElsesExecution() throws Exception {
        OperationExecution execution = runningExecution(envA, operator);
        lockService.acquireLock(envA.getEnvironmentId(), otherUser.getUserId(), "taking over", null);

        mockMvc.perform(post(opsUrl(envA) + "/" + execution.getExecutionId() + "/cancel").with(as(otherUser)))
                .andExpect(status().isOk());

        assertThat(statusOf(execution)).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void anAdminCanCancelAnyExecution() throws Exception {
        OperationExecution execution = runningExecution(envA, operator);

        mockMvc.perform(post(opsUrl(envA) + "/" + execution.getExecutionId() + "/cancel").with(asAdmin()))
                .andExpect(status().isOk());

        assertThat(statusOf(execution)).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void theInitiatorGetsTheirExecutionWithDetails() throws Exception {
        OperationExecution execution = runningExecution(envA, operator);

        mockMvc.perform(get(opsUrl(envA) + "/" + execution.getExecutionId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(execution.getExecutionId()));
    }
}
