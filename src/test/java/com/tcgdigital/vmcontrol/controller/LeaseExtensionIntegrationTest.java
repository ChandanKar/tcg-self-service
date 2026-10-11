package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.LeaseSource;
import com.tcgdigital.vmcontrol.model.LeaseStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Extend within the maximum (200), beyond it (202 + owner approval), and the decision rules (E15-T04). */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseExtensionIntegrationTest extends SecuredWebTestBase {

    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private EnvironmentLeaseParticipantRepository participants;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private Environment env;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Extend lease");
        env.setLeaseEnabled(true);
        env.setLeaseMaxMinutes(720);
        env = environmentRepository.saveAndFlush(env);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
    }

    /** An active lease started {@code startedAgo} ago that ends {@code endsIn} from now. */
    private EnvironmentLease lease(Duration startedAgo, Duration endsIn) {
        EnvironmentLease lease = new EnvironmentLease();
        lease.setLeaseId(UUID.randomUUID().toString());
        lease.setEnvironment(env);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setSource(LeaseSource.START);
        lease.setStartedByUserId(operator.getUserId());
        lease.setStartedAt(Timestamp.from(Instant.now().minus(startedAgo)));
        lease.setEndsAt(Timestamp.from(Instant.now().plus(endsIn)));
        lease = leases.saveAndFlush(lease);
        participants.saveAndFlush(new EnvironmentLeaseParticipant(lease.getLeaseId(), operator.getUserId(),
                Timestamp.from(Instant.now())));
        return lease;
    }

    private String extendUrl() {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/lease/extend";
    }

    private static String body(int minutes, String reason) {
        return "{\"minutes\":" + minutes + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private Instant endsAt(EnvironmentLease lease) {
        return leases.findById(lease.getLeaseId()).orElseThrow().getEndsAt().toInstant();
    }

    @Test
    void anExtensionWithinTheMaximumAppliesAtOnce() throws Exception {
        EnvironmentLease lease = lease(Duration.ofHours(1), Duration.ofHours(1));
        mockMvc.perform(post(extendUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body(60, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(lease.getLeaseId()))
                .andExpect(jsonPath("$.extendedMinutesTotal").value(60));
        assertThat(endsAt(lease)).isCloseTo(Instant.now().plus(Duration.ofHours(2)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void beyondTheMaximumTheOwnersDecide() throws Exception {
        // Owner maximum 12 h, started 11 h ago, ends in 1 h: +2 h would make 14 h.
        EnvironmentLease lease = lease(Duration.ofHours(11), Duration.ofHours(1));
        Instant before = endsAt(lease);
        String response = mockMvc.perform(post(extendUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(120, "Release rehearsal runs late")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(response).get("requestId").asText();
        assertThat(endsAt(lease)).isEqualTo(before);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE "
                + "type = 'LEASE_EXTENSION_REQUESTED' AND user_id = ?", Integer.class, admin.getUserId())).isEqualTo(1));

        // One pending request per lease.
        expectError(mockMvc.perform(post(extendUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(120, "again"))), 409);

        // The lease shows the pending request; owners list it.
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/lease").with(asUser()))
                .andExpect(jsonPath("$.pendingExtension.requestId").value(requestId));
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/lease/extension-requests").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId));
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/lease/extension-requests").with(asUser()))
                .andExpect(status().isForbidden());

        // A USER cannot decide; the owner approves; a second decision is a conflict.
        String approve = "/api/v1/lease-extension-requests/" + requestId + "/approve";
        mockMvc.perform(post(approve).with(asUser()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(approve).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        expectError(mockMvc.perform(post(approve).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}")), 409);

        assertThat(endsAt(lease)).isCloseTo(before.plus(Duration.ofHours(2)), within(1, ChronoUnit.SECONDS));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE "
                + "type = 'LEASE_EXTENSION_DECIDED' AND user_id = ?", Integer.class, operator.getUserId())).isEqualTo(1));
    }

    @Test
    void aDeniedRequestLeavesTheEndAlone() throws Exception {
        EnvironmentLease lease = lease(Duration.ofHours(11), Duration.ofHours(1));
        Instant before = endsAt(lease);
        JsonNode request = objectMapper.readTree(mockMvc.perform(post(extendUrl()).with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(body(120, "late")))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/lease-extension-requests/" + request.get("requestId").asText() + "/deny")
                        .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"not today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));
        assertThat(endsAt(lease)).isEqualTo(before);
    }

    @Test
    void beyondTheAbsoluteMaximumIsRejectedAndViewersCannotExtend() throws Exception {
        lease(Duration.ofHours(71), Duration.ofMinutes(30));
        expectError(mockMvc.perform(post(extendUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(120, "way too long"))), 400);
        mockMvc.perform(post(extendUrl()).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(body(30, null)))
                .andExpect(status().isForbidden());
        expectError(mockMvc.perform(post(extendUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(5, null))), 400);
    }
}
