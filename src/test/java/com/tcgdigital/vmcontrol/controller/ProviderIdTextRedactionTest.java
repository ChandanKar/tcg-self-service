package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationDetail;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.OperationDetailRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U01-T03: stored text keeps the AWS ids for administrators, and everyone else reads it with them masked:
 * operation step errors, audit details and VM state history.
 */
class ProviderIdTextRedactionTest extends SecuredWebTestBase {

    private static final String ID = "i-0123456789abcdef0";
    private static final String AWS_TEXT = "The instance " + ID + " is not in a state from which it can be started.";

    @Autowired private OperationExecutionRepository executions;
    @Autowired private OperationDetailRepository details;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private VmStateHistoryRepository history;

    private Environment env;
    private Vm vm;
    private OperationExecution execution;

    @BeforeEach
    void seed() {
        env = newEnvironment("Redaction");
        VmGroup group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.STOPPED);

        execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(env);
        execution.setOperationType(OperationType.START);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setInitiatedByUserId(admin.getUserId());
        execution.setStartedAt(new Timestamp(System.currentTimeMillis()));
        execution.setTotalTargets(1);
        execution.setErrorMessage("1 VM failed: " + AWS_TEXT);
        execution = executions.saveAndFlush(execution);
        OperationDetail detail = new OperationDetail();
        detail.setDetailId(UUID.randomUUID().toString());
        detail.setExecution(execution);
        detail.setTargetType("vm");
        detail.setTargetId(vm.getVmId());
        detail.setTargetName(vm.getName());
        detail.setAction("start");
        detail.setStatus("failed");
        detail.setSequencePosition(1);
        detail.setErrorMessage("AWS cannot start this VM in its current state. - AWS said: " + AWS_TEXT);
        details.saveAndFlush(detail);

        AuditLog log = new AuditLog();
        log.setLogId(UUID.randomUUID().toString());
        log.setEnvironmentId(env.getEnvironmentId());
        log.setAction(com.tcgdigital.vmcontrol.model.AuditAction.VM_UPDATED);
        log.setTargetType("vm");
        log.setTargetId(vm.getVmId());
        log.setTargetName(vm.getName());
        log.setDetails("providerVmId: i-0aaaaaaaaaaaaaaa1 -> " + ID + "; role arn:aws:iam::123456789012:role/App");
        auditLogs.saveAndFlush(log);

        history.saveAndFlush(VmStateHistory.builder().historyId(UUID.randomUUID().toString()).vm(vm)
                .previousStatus(VmStatus.RUNNING).newStatus(VmStatus.STOPPED).changeSource("state_sync")
                .details("Stopped outside the platform: " + ID).build());

        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
    }

    private String body(String url, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String operation(RequestPostProcessor who) throws Exception {
        return body("/api/v1/environments/" + env.getEnvironmentId() + "/operations/" + execution.getExecutionId(), who);
    }

    private String audit(RequestPostProcessor who) throws Exception {
        return body("/api/v1/audit/logs?environmentId=" + env.getEnvironmentId(), who);
    }

    private String stateHistory(RequestPostProcessor who) throws Exception {
        return body("/api/v1/monitoring/vms/" + vm.getVmId() + "/history", who);
    }

    @Test
    void anAdministratorReadsTheTextAsStored() throws Exception {
        assertThat(operation(asAdmin())).contains(ID);
        assertThat(audit(asAdmin())).contains(ID).contains("arn:aws:iam::123456789012:role/App");
        assertThat(stateHistory(asAdmin())).contains(ID);
    }

    @Test
    void othersReadItWithTheIdsMasked() throws Exception {
        String operation = operation(asUser());
        assertThat(operation).doesNotContain(ID).contains("The instance i-… is not in a state")
                .contains("AWS cannot start this VM in its current state.");
        assertThat(stateHistory(asUser())).doesNotContain(ID).contains("Stopped outside the platform: i-…");

        String audit = audit(asEnvAdmin());
        assertThat(audit).doesNotContain(ID).doesNotContain("i-0aaaaaaaaaaaaaaa1").doesNotContain("arn:aws")
                .contains("providerVmId: i-… -> i-…");
        assertThat(operation(asEnvAdmin())).doesNotContain(ID);
    }
}
