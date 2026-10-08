package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access durations are bounded on every body (1..1825 days) and by the configured request and
 * grant maxima (180 / 365 by default); review bodies are validated (E04-T03, M18).
 */
class AccessDurationValidationTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentAccessRequestRepository requests;

    @Autowired
    private EnvironmentAccessService accessService;

    private Environment env;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Durations");
    }

    private static String requestBody(int days) {
        return "{\"accessLevel\":\"USER\",\"businessJustification\":\"Need access for the release\",\"durationDays\":" + days + "}";
    }

    private EnvironmentAccessRequest pendingRequest() {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for the release");
        return accessService.createAccessRequest(env.getEnvironmentId(), operator.getUserId(), dto);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5000})
    void createRequestRejectsOutOfRangeDurations(int days) throws Exception {
        expectError(mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/access-requests")
                .with(asUser()).contentType(MediaType.APPLICATION_JSON).content(requestBody(days))), 400)
                .andExpect(jsonPath("$.errors[0].field").value("durationDays"));

        assertThat(requests.findAll()).isEmpty();
    }

    @Test
    void createRequestRejectsMoreThanTheConfiguredRequestMaximum() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/access-requests")
                .with(asUser()).contentType(MediaType.APPLICATION_JSON).content(requestBody(365))), 400)
                .andExpect(jsonPath("$.message").value("You can request at most 180 days"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5000})
    void approveRejectsOutOfRangeDurationsAndLeavesTheRequestPending(int days) throws Exception {
        EnvironmentAccessRequest request = pendingRequest();

        expectError(mockMvc.perform(post("/api/v1/access-requests/" + request.getRequestId() + "/approve")
                .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"durationDays\":" + days + "}")), 400);

        assertThat(requests.findById(request.getRequestId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test
    void approveAboveTheGrantMaximumIsRejectedAndTheRequestStaysPending() throws Exception {
        EnvironmentAccessRequest request = pendingRequest();

        expectError(mockMvc.perform(post("/api/v1/access-requests/" + request.getRequestId() + "/approve")
                .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"durationDays\":400}")), 400)
                .andExpect(jsonPath("$.message").value("Access can be granted for at most 365 days"));

        assertThat(requests.findById(request.getRequestId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test
    void validApprovalSetsTheExpiryThatManyDaysAhead() throws Exception {
        EnvironmentAccessRequest request = pendingRequest();

        mockMvc.perform(post("/api/v1/access-requests/" + request.getRequestId() + "/approve")
                        .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"durationDays\":30}"))
                .andExpect(status().isOk());

        EnvironmentAccess grant = environmentAccessRepository.findAll().stream()
                .filter(ea -> ea.getUser().getUserId().equals(operator.getUserId()))
                .findFirst().orElseThrow();
        Instant expected = Instant.now().plus(Duration.ofDays(30));
        assertThat(Duration.between(grant.getExpiresAt().toInstant(), expected).abs()).isLessThan(Duration.ofMinutes(5));
    }

    @Test
    void denyBodyIsValidated() throws Exception {
        EnvironmentAccessRequest request = pendingRequest();
        String longNotes = "x".repeat(501);

        expectError(mockMvc.perform(post("/api/v1/access-requests/" + request.getRequestId() + "/deny")
                .with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"" + longNotes + "\"}")), 400);

        assertThat(requests.findById(request.getRequestId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5000})
    void directGrantsRejectOutOfRangeDurations(int days) throws Exception {
        String body = "{\"userEmail\":\"" + viewer.getEmail() + "\",\"environmentId\":\"" + env.getEnvironmentId()
                + "\",\"accessLevel\":\"VIEWER\",\"scopeType\":\"ENVIRONMENT\",\"durationDays\":" + days + "}";

        expectError(mockMvc.perform(post("/api/v1/access-grants").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 400);
        expectError(mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/access").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userEmail\":\"" + viewer.getEmail() + "\",\"accessLevel\":\"VIEWER\",\"durationDays\":" + days + "}")), 400);

        assertThat(environmentAccessRepository.findAll()).isEmpty();
    }

    @Test
    void grantAboveTheGrantMaximumIsRejected() throws Exception {
        String body = "{\"userEmail\":\"" + viewer.getEmail() + "\",\"environmentId\":\"" + env.getEnvironmentId()
                + "\",\"accessLevel\":\"VIEWER\",\"scopeType\":\"ENVIRONMENT\",\"durationDays\":400}";

        expectError(mockMvc.perform(post("/api/v1/access-grants").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 400)
                .andExpect(jsonPath("$.message").value("Access can be granted for at most 365 days"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5000})
    void editingAGrantRejectsOutOfRangeDurations(int days) throws Exception {
        EnvironmentAccess existing = grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        expectError(mockMvc.perform(patch("/api/v1/access-grants/" + existing.getAccessId()).with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"durationDays\":" + days + "}")), 400);

        assertThat(environmentAccessRepository.findById(existing.getAccessId()).orElseThrow().getExpiresAt()).isNull();
    }

    @Test
    void onboardingRejectsAZeroDayInitialGrant() throws Exception {
        String body = "{\"email\":\"new-person@example.com\",\"displayName\":\"New Person\",\"initialGrant\":{"
                + "\"environmentId\":\"" + env.getEnvironmentId() + "\",\"accessLevel\":\"VIEWER\","
                + "\"scopeType\":\"ENVIRONMENT\",\"durationDays\":0}}";

        expectError(mockMvc.perform(post("/api/v1/users").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 400)
                .andExpect(jsonPath("$.errors[0].field").value("initialGrant.durationDays"));
    }
}
