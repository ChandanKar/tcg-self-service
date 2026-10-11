package com.tcgdigital.vmcontrol.service;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.ApprovalRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lease extensions under approval policies (E25-T05): held beyond max_runtime_hours (inside the platform
 * lease maximum, so an approval applies at once) with the lease
 * unchanged, moved on approval, and EXPIRED ("lease ended") when the lease ends first.
 */
@TestPropertySource(properties = "lease.enabled=true")
class ApprovalExtensionIntegrationTest extends SecuredWebTestBase {

    @Autowired private ApprovalPolicyService policyService;
    @Autowired private ApprovalRequestService requestService;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private EnvironmentLeaseParticipantRepository participants;

    private Environment env;
    private User approver;
    private EnvironmentLease lease;

    private Object policyTarget() {
        Object target = AopTestUtils.getTargetObject(policyService);
        return target;
    }

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(policyTarget(), "enabled", true);
        env = newEnvironment("Long runner");
        env.setLeaseEnabled(true);
        env.setLeaseMaxMinutes(1440);
        env = environmentRepository.saveAndFlush(env);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        approver = newUser("lease-approver@secured.test", false, false);

        lease = new EnvironmentLease();
        lease.setLeaseId(UUID.randomUUID().toString());
        lease.setEnvironment(env);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setSource(LeaseSource.START);
        lease.setStartedByUserId(operator.getUserId());
        lease.setStartedAt(Timestamp.from(Instant.now().minus(Duration.ofHours(5))));
        lease.setEndsAt(Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        lease = leases.saveAndFlush(lease);
        participants.saveAndFlush(new EnvironmentLeaseParticipant(lease.getLeaseId(), operator.getUserId(), Timestamp.from(Instant.now())));

        mockMvc.perform(post("/api/v1/approval-policies").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"name\":\"Eight hours\",\"maxRuntimeHours\":8,"
                        + "\"approvers\":[{\"type\":\"USER\",\"approverId\":\"" + approver.getUserId() + "\"}]}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(policyTarget(), "enabled", false);
    }

    private Instant endsAt() {
        return leases.findById(lease.getLeaseId()).orElseThrow().getEndsAt().toInstant();
    }

    private String extend(int minutes) throws Exception {
        String body = mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/lease/extend").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":" + minutes + ",\"reason\":\"Long test run\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.approvalRequestId");
    }

    @Test
    void anExtensionPastTheLimitWaitsThenMovesTheLeaseEnd() throws Exception {
        Instant before = endsAt();
        String id = extend(240);
        assertThat(endsAt()).isEqualTo(before);
        ApprovalRequest held = requests.findById(id).orElseThrow();
        assertThat(held.getRequestType()).isEqualTo(ApprovalRequest.Type.EXTEND);
        assertThat(held.getReasons()).contains("10 h is longer than 8 h");

        mockMvc.perform(post("/api/v1/approval-requests/" + id + "/approve").with(as(approver))).andExpect(status().isOk());
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(endsAt()).isCloseTo(before.plus(Duration.ofMinutes(240)), within(Duration.ofSeconds(5)));
    }

    @Test
    void aShortExtensionWithinTheLimitIsNotHeld() throws Exception {
        lease.setStartedAt(Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
        leases.saveAndFlush(lease);
        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/lease/extend").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":60}"))
                .andExpect(status().isOk());
        assertThat(requests.count()).isZero();
    }

    @Test
    void whenTheLeaseEndsFirstTheRequestExpires() throws Exception {
        String waiting = extend(240);
        String approvedLate = extend(180);

        EnvironmentLease ended = leases.findById(lease.getLeaseId()).orElseThrow();
        ended.setStatus(LeaseStatus.ENDED);
        ended.setEndedAt(Timestamp.from(Instant.now()));
        leases.saveAndFlush(ended);

        // Approved after the lease ended: nothing to extend.
        mockMvc.perform(post("/api/v1/approval-requests/" + approvedLate + "/approve").with(as(approver))).andExpect(status().isOk());
        ApprovalRequest late = requests.findById(approvedLate).orElseThrow();
        assertThat(late.getStatus()).isEqualTo(ApprovalRequest.Status.EXPIRED);
        assertThat(late.getStatusReason()).isEqualTo("lease ended");

        // Still pending: the expiry job notices.
        assertThat(requestService.expire()).isEqualTo(1);
        ApprovalRequest pending = requests.findById(waiting).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ApprovalRequest.Status.EXPIRED);
        assertThat(pending.getStatusReason()).isEqualTo("lease ended");
    }
}
