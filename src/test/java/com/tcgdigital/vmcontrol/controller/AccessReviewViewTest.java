package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviewers see what they approve: pending requests carry the requester's current grant on the
 * same scope and the extension flag, and approval can override the duration or grant with no
 * expiry (E04-T07, M20).
 */
class AccessReviewViewTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentAccessService accessService;

    private Environment env;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Review");
    }

    private EnvironmentAccessRequest request(User requester, Integer days) {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for the release");
        dto.setDurationDays(days);
        return accessService.createAccessRequest(env.getEnvironmentId(), requester.getUserId(), dto);
    }

    @Test
    void pendingListShowsCurrentAccessForAnExtensionAndNothingForANewRequest() throws Exception {
        EnvironmentAccess current = grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        current.setExpiresAt(Timestamp.from(Instant.now().plus(3, ChronoUnit.DAYS)));
        environmentAccessRepository.saveAndFlush(current);
        EnvironmentAccessRequest extension = request(operator, 7);
        EnvironmentAccessRequest fresh = request(viewer, 30);

        String ext = "$[?(@.requestId == '" + extension.getRequestId() + "')]";
        String neu = "$[?(@.requestId == '" + fresh.getRequestId() + "')]";
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/access-requests").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(ext + ".extension").value(true))
                .andExpect(jsonPath(ext + ".currentAccessLevel").value("USER"))
                .andExpect(jsonPath(ext + ".currentExpiresAt").isNotEmpty())
                .andExpect(jsonPath(ext + ".durationDays").value(7))
                .andExpect(jsonPath(ext + ".scopeType").value("ENVIRONMENT"))
                .andExpect(jsonPath(neu + ".extension").value(false))
                .andExpect(jsonPath(neu + ".currentAccessLevel", contains(nullValue())))
                .andExpect(jsonPath(neu + ".currentExpiresAt", contains(nullValue())));

        mockMvc.perform(get("/api/v1/access-requests/pending").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(ext + ".currentAccessLevel").value("USER"));
    }

    @Test
    void approvingWithNoExpiryIgnoresTheRequestedDuration() throws Exception {
        EnvironmentAccessRequest pending = request(viewer, 30);

        mockMvc.perform(post("/api/v1/access-requests/" + pending.getRequestId() + "/approve").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clearExpiry\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));

        assertThat(environmentAccessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(viewer.getUserId()))
                .singleElement()
                .satisfies(ea -> assertThat(ea.getExpiresAt()).isNull());
    }

    @Test
    void approvingAsRequestedUsesTheRequestedDays() throws Exception {
        EnvironmentAccessRequest pending = request(viewer, 30);

        mockMvc.perform(post("/api/v1/access-requests/" + pending.getRequestId() + "/approve").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationDays\":null}"))
                .andExpect(status().isOk());

        EnvironmentAccess grant = environmentAccessRepository.findAll().stream()
                .filter(ea -> ea.getUser().getUserId().equals(viewer.getUserId())).findFirst().orElseThrow();
        long days = ChronoUnit.HOURS.between(Instant.now(), grant.getExpiresAt().toInstant()) / 24;
        assertThat(days).isBetween(29L, 30L);
    }
}
