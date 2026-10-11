package com.tcgdigital.vmcontrol.controller;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lease endpoints with the feature on (E15-T01): policy role matrix, bounds, audit, lease payload. */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseControllerIntegrationTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentLeaseRepository leases;

    @Autowired
    private EnvironmentLeaseParticipantRepository participants;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Environment env;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Leases");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
    }

    private String policyUrl() {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/lease-policy";
    }

    private static String policy(boolean enabled, Integer def, Integer max) {
        return "{\"enabled\":" + enabled + ",\"defaultMinutes\":" + def + ",\"maxMinutes\":" + max + "}";
    }

    @Test
    void anOwnerSetsThePolicyAndTheChangeIsAudited() throws Exception {
        mockMvc.perform(put(policyUrl()).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(policy(true, 240, 720)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.effectiveDefaultMinutes").value(240))
                .andExpect(jsonPath("$.effectiveMaxMinutes").value(720))
                .andExpect(jsonPath("$.platformMaxMinutes").value(720));

        Environment saved = environmentRepository.findById(env.getEnvironmentId()).orElseThrow();
        assertThat(saved.getLeaseEnabled()).isTrue();
        assertThat(saved.getLeaseMaxMinutes()).isEqualTo(720);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'ENVIRONMENT_UPDATED' AND environment_id = ? "
                        + "AND details LIKE '%leaseMaxMinutes%'", Integer.class, env.getEnvironmentId())).isEqualTo(1));

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEnabled").value(true));
    }

    @Test
    void aMaximumAboveThePlatformLimitIsRejected() throws Exception {
        expectError(mockMvc.perform(put(policyUrl()).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(policy(true, 240, 900))), 400);
        assertThat(environmentRepository.findById(env.getEnvironmentId()).orElseThrow().getLeaseEnabled()).isFalse();
    }

    @Test
    void usersReadThePolicyButCannotChangeIt() throws Exception {
        mockMvc.perform(get(policyUrl()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.canEdit").value(false));
        mockMvc.perform(put(policyUrl()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(policy(true, 60, 120)))
                .andExpect(status().isForbidden());
        // No access at all
        mockMvc.perform(get(policyUrl()).with(asViewer())).andExpect(status().isForbidden());
    }

    @Test
    void theLeaseEndpointReturnsTheActiveLeaseOr204() throws Exception {
        String url = "/api/v1/environments/" + env.getEnvironmentId() + "/lease";
        mockMvc.perform(get(url).with(asUser())).andExpect(status().isNoContent());

        EnvironmentLease lease = new EnvironmentLease();
        lease.setLeaseId(UUID.randomUUID().toString());
        lease.setEnvironment(env);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setSource(LeaseSource.START);
        lease.setStartedByUserId(operator.getUserId());
        lease.setStartedAt(Timestamp.from(Instant.now()));
        lease.setEndsAt(Timestamp.from(Instant.now().plus(Duration.ofHours(4))));
        leases.saveAndFlush(lease);
        participants.saveAndFlush(new EnvironmentLeaseParticipant(lease.getLeaseId(), operator.getUserId(),
                Timestamp.from(Instant.now())));

        mockMvc.perform(get(url).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source").value("START"))
                .andExpect(jsonPath("$.endsAt").exists())
                .andExpect(jsonPath("$.participants[0].userId").value(operator.getUserId()))
                .andExpect(jsonPath("$.permissions.canExtend").value(true))
                .andExpect(jsonPath("$.permissions.canManagePolicy").value(false));
    }
}
