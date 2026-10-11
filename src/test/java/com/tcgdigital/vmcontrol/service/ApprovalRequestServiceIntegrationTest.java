package com.tcgdigital.vmcontrol.service;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.ApprovalRequest;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.ApprovalRequestRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts held for approval (E25-T04): ANY, ALL, deny, self-approval, concurrent approvals,
 * expiry, and the plain path when approvals are off. Cloud calls are mocked.
 */
class ApprovalRequestServiceIntegrationTest extends SecuredWebTestBase {

    @Autowired private ApprovalPolicyService policyService;
    @Autowired private ApprovalRequestService requestService;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private NotificationRepository notifications;

    private Environment env;
    private User approverA;
    private User approverB;

    private Object policyTarget() {
        Object target = AopTestUtils.getTargetObject(policyService);
        return target;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(policyTarget(), "enabled", true);
        env = newEnvironment("Held");
        VmGroup group = newGroup(env, "app");
        newVm(group, "app-1", VmStatus.STOPPED);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        approverA = newUser("approver-a@secured.test", false, false);
        approverB = newUser("approver-b@secured.test", false, false);
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(policyTarget(), "enabled", false);
    }

    /** A policy every start matches (8 h by default > 1 h). */
    private void policy(String mode) throws Exception {
        mockMvc.perform(post("/api/v1/approval-policies").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"name\":\"Long\",\"maxRuntimeHours\":1,"
                        + "\"approverMode\":\"" + mode + "\",\"approvers\":[{\"type\":\"USER\",\"approverId\":\"" + approverA.getUserId()
                        + "\"},{\"type\":\"USER\",\"approverId\":\"" + approverB.getUserId() + "\"}]}"))
                .andExpect(status().isCreated());
    }

    private String startAsRequester() throws Exception {
        String body = mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/operations").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationType\":\"START\",\"skipAlreadyInTargetState\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.reasons[0]").value("8 h is longer than 1 h"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.approvalRequestId");
    }

    private long executionCount() {
        return executions.findAll().stream().filter(e -> env.getEnvironmentId().equals(e.getEnvironment().getEnvironmentId())).count();
    }

    @Test
    void anyOneApprovalStartsItOnceAsTheRequester() throws Exception {
        policy("ANY");
        String id = startAsRequester();
        assertThat(executionCount()).isZero();
        awaitAsync(() -> assertThat(notifications.findAll()).filteredOn(n -> "APPROVAL_REQUESTED".equals(n.getType().name()))
                .extracting(n -> n.getUserId()).containsExactlyInAnyOrder(approverA.getUserId(), approverB.getUserId()));

        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverA)))
                .andExpect(status().isOk());
        ApprovalRequest done = requests.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(done.getExecutionId()).isNotNull();
        assertThat(executions.findById(done.getExecutionId()).orElseThrow().getInitiatedByUserId()).isEqualTo(operator.getUserId());
        assertThat(executionCount()).isEqualTo(1);

        expectError(mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverB))), 409);
        mockMvc.perform(get("/api/v1/approval-requests").param("view", "mine").with(asUser()))
                .andExpect(jsonPath("$[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$[0].approvers[?(@.userId == '" + approverA.getUserId() + "')].decision").value("APPROVED"));
    }

    @Test
    void allNeedsEveryApprover() throws Exception {
        policy("ALL");
        String id = startAsRequester();
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverA)))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(executionCount()).isZero();
        mockMvc.perform(get("/api/v1/approval-requests").param("view", "to-review").with(as(approverB)))
                .andExpect(jsonPath("$[0].canDecide").value(true));
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverB))).andExpect(status().isOk());
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(executionCount()).isEqualTo(1);
    }

    @Test
    void oneDenialDeniesAndTheRequesterIsTold() throws Exception {
        policy("ALL");
        String id = startAsRequester();
        expectError(mockMvc.perform(post("/api/v1/approval-requests/" + id + "/deny").with(as(approverB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")), 400);
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/deny").with(as(approverB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Use the small one\"}"))
                .andExpect(jsonPath("$.status").value("DENIED"));
        expectError(mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverA))), 409);
        assertThat(executionCount()).isZero();
        awaitAsync(() -> assertThat(notifications.findAll())
                .filteredOn(n -> n.getUserId().equals(operator.getUserId()) && "APPROVAL_DENIED".equals(n.getType().name()))
                .singleElement().satisfies(n -> assertThat(n.getMessage()).contains("Use the small one")));
    }

    @Test
    void theRequesterCannotApproveButCanCancel() throws Exception {
        policy("ANY");
        String id = startAsRequester();
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/approval-requests/" + id).with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/cancel").with(as(approverA))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/cancel").with(asUser()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        expectError(mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverA))), 409);
    }

    @Test
    void twoSimultaneousApprovalsStartItOnce() throws Exception {
        policy("ANY");
        String id = startAsRequester();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (User approver : List.of(approverA, approverB)) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    requestService.approve(id, approver.getUserId(), null);
                    return "ok";
                } catch (ConflictException e) {
                    return "conflict";
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).contains("ok");
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(executionCount()).isEqualTo(1);
    }

    @Test
    void anUndecidedRequestExpires() throws Exception {
        policy("ANY");
        String id = startAsRequester();
        ApprovalRequest r = requests.findById(id).orElseThrow();
        r.setExpiresAt(Timestamp.from(Instant.now().minusSeconds(60)));
        requests.saveAndFlush(r);

        assertThat(requestService.expire()).isEqualTo(1);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ApprovalRequest.Status.EXPIRED);
        expectError(mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approverA))), 409);
        assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(operator.getUserId())
                && n.getTitle().startsWith("Request expired")).hasSize(1);
    }

    @Test
    void withApprovalsOffTheStartRunsAtOnce() throws Exception {
        policy("ANY");
        ReflectionTestUtils.setField(policyTarget(), "enabled", false);
        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/operations").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operationType\":\"START\",\"skipAlreadyInTargetState\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty());
        assertThat(requests.count()).isZero();
    }
}
