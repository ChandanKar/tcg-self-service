package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.OperationDetail;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.OperationDetailRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * U01-T05: the administrators-only rule held across the API. One environment holds an instance id, a
 * volume id, a failed step, an audit row, a notification and state history that all quote AWS ids; every
 * GET an environment admin, user or viewer can reach for it answers without one.
 */
class ProviderIdExposureSecurityTest extends SecuredWebTestBase {

    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final String VOLUME = "vol-0123456789abcdef0";
    private static final Pattern AWS_ID = Pattern.compile("\\b(i|vol)-[0-9a-f]{8,17}\\b|arn:aws");

    @Autowired private VmInventorySnapshotRepository inventory;
    @Autowired private VmVolumeSnapshotRepository volumes;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private OperationDetailRepository details;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private NotificationRepository notifications;
    @Autowired private VmStateHistoryRepository history;

    private Environment env;
    private VmGroup group;
    private Vm vm;
    private OperationExecution execution;

    @BeforeEach
    void seed() {
        env = newEnvironment("Exposure");
        group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.STOPPED);
        vm.setProviderVmId(INSTANCE);
        vm = vmRepository.saveAndFlush(vm);

        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(vm);
        snapshot.setProvider(CloudProvider.AWS);
        snapshot.setProviderVmId(INSTANCE);
        snapshot.setInstanceType("t3.medium");
        snapshot.setPrivateIp("10.0.1.15");
        snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
        inventory.saveAndFlush(snapshot);
        VmVolumeSnapshot volume = new VmVolumeSnapshot();
        volume.setVm(vm);
        volume.setVolumeId(VOLUME);
        volume.setDeviceName("/dev/xvda");
        volume.setSizeGib(30);
        volume.setLastRefreshedAt(Timestamp.from(Instant.now()));
        volumes.saveAndFlush(volume);

        String awsText = "The instance " + INSTANCE + " is not in a state from which it can be started.";
        execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(env);
        execution.setOperationType(OperationType.START);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setInitiatedByUserId(operator.getUserId());
        execution.setStartedAt(new Timestamp(System.currentTimeMillis()));
        execution.setTotalTargets(1);
        execution.setErrorMessage(awsText);
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
        detail.setErrorMessage(awsText);
        details.saveAndFlush(detail);

        AuditLog log = new AuditLog();
        log.setLogId(UUID.randomUUID().toString());
        log.setUserId(operator.getUserId());
        log.setEnvironmentId(env.getEnvironmentId());
        log.setAction(AuditAction.VM_UPDATED);
        log.setTargetType("vm");
        log.setTargetId(vm.getVmId());
        log.setTargetName(vm.getName());
        log.setDetails("providerVmId: " + INSTANCE + "; role arn:aws:iam::123456789012:role/App");
        auditLogs.saveAndFlush(log);

        for (User user : List.of(envAdmin, operator, viewer)) {
            Notification n = new Notification();
            n.setNotificationId(UUID.randomUUID().toString());
            n.setUserId(user.getUserId());
            n.setType(NotificationType.OPERATION_FAILED);
            n.setTitle("Start failed");
            n.setMessage(awsText); // written before U01-T04 masked new rows
            notifications.saveAndFlush(n);
        }
        history.saveAndFlush(VmStateHistory.builder().historyId(UUID.randomUUID().toString()).vm(vm)
                .previousStatus(VmStatus.RUNNING).newStatus(VmStatus.STOPPED).changeSource("state_sync")
                .details("Stopped outside the platform: " + INSTANCE).build());

        grantEnv(envAdmin, env.getEnvironmentId(), AccessLevel.ADMIN);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        when(ec2Service.listInstances(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ap-south-1")))
                .thenReturn(List.of());
    }

    private static <T> org.mockito.stubbing.OngoingStubbing<T> when(T call) {
        return org.mockito.Mockito.when(call);
    }

    private List<String> urls() {
        String e = "/api/v1/environments/" + env.getEnvironmentId();
        return List.of(
                "/api/v1/environments",
                e,
                e + "/vms",
                e + "/vms/" + group.getGroupId() + "/page",
                e + "/vms/" + vm.getVmId(),
                e + "/vms/" + vm.getVmId() + "/inventory",
                e + "/vms/" + vm.getVmId() + "/metrics",
                e + "/vms/" + vm.getVmId() + "/utilization-summary",
                e + "/vms/review",
                e + "/operations",
                e + "/operations/" + execution.getExecutionId(),
                e + "/cost",
                e + "/spot-summary",
                "/api/v1/audit/logs?environmentId=" + env.getEnvironmentId(),
                "/api/v1/audit/logs/my",
                "/api/v1/users/me/activity",
                "/api/v1/notifications",
                "/api/v1/monitoring/vms/" + vm.getVmId() + "/history",
                "/api/v1/dashboard/summary",
                "/api/ec2/instances?region=ap-south-1");
    }

    private MockHttpServletResponse read(String url, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get(url).with(who)).andReturn().getResponse();
    }

    @Test
    void noNonAdministratorReceivesAnAwsIdFromAnyEndpoint() throws Exception {
        for (RequestPostProcessor who : List.of(asEnvAdmin(), asUser(), asViewer())) {
            for (String url : urls()) {
                MockHttpServletResponse response = read(url, who);
                assertThat(response.getStatus()).as("status of %s", url).isLessThan(500);
                assertThat(AWS_ID.matcher(response.getContentAsString()).find())
                        .as("AWS id in %s: %s", url, response.getContentAsString()).isFalse();
            }
            // the sweep is meaningful: each role really reads the VM, the failed run and the history
            String e = "/api/v1/environments/" + env.getEnvironmentId();
            assertThat(read(e + "/vms/" + vm.getVmId(), who).getContentAsString()).contains("10.0.1.15");
            assertThat(read(e + "/operations/" + execution.getExecutionId(), who).getContentAsString())
                    .contains("The instance i-… is not in a state");
            assertThat(read("/api/v1/monitoring/vms/" + vm.getVmId() + "/history", who).getContentAsString())
                    .contains("Stopped outside the platform: i-…");
        }
        assertThat(read("/api/v1/notifications", asUser()).getContentAsString()).contains("The instance i-…");
    }

    @Test
    void anAdministratorStillGetsThem() throws Exception {
        String e = "/api/v1/environments/" + env.getEnvironmentId();
        assertThat(read(e + "/vms/" + vm.getVmId(), asAdmin()).getContentAsString()).contains(INSTANCE);
        assertThat(read(e + "/vms/" + vm.getVmId() + "/inventory", asAdmin()).getContentAsString()).contains(VOLUME);
        assertThat(read(e + "/operations/" + execution.getExecutionId(), asAdmin()).getContentAsString()).contains(INSTANCE);
    }

    @Test
    void exportsThatCarryIdsAreForAdministratorsOnly() throws Exception {
        for (RequestPostProcessor who : List.of(asEnvAdmin(), asUser(), asViewer())) {
            assertThat(read("/api/v1/cost-management/export/focus?month=2026-09", who).getStatus()).isEqualTo(403);
        }
    }
}
