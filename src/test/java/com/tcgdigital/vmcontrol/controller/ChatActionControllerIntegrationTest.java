package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.ChatActionTokenRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.chat.ChatActionTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One-click chat actions (E24-T04): approve runs exactly like the Pending Requests page and is
 * audited "via chat"; a link works once; bad links are 400; the normal permission checks apply;
 * POST needs CSRF.
 */
class ChatActionControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/chat-actions/";

    @Autowired private ChatActionTokenService tokens;
    @Autowired private ChatActionTokenRepository tokenRepository;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private AuditLogRepository auditLogs;

    private Environment env;
    private EnvironmentAccessRequest request;

    @BeforeEach
    void pendingRequest() {
        env = newEnvironment("Payments QA");
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Release testing");
        request = accessService.createAccessRequest(env.getEnvironmentId(), operator.getUserId(), dto);
    }

    private String approveLink() {
        return tokens.issue(ChatActionType.APPROVE_ACCESS_REQUEST, env.getEnvironmentId(), request.getRequestId());
    }

    @Test
    void aReviewerApprovesFromTheLinkOnce() throws Exception {
        String token = approveLink();
        mockMvc.perform(get(URL + token).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APPROVE_ACCESS_REQUEST"))
                .andExpect(jsonPath("$.environmentName").value("Payments QA"))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.startsWith("Approve operator's request for USER access on Payments QA")))
                .andExpect(jsonPath("$.confirmLabel").value("Approve"));

        mockMvc.perform(post(URL + token).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Approved operator's request on Payments QA."));
        EnvironmentAccessRequest decided = accessService.getAccessRequest(request.getRequestId());
        assertThat(decided.getStatus().name()).isEqualTo("APPROVED");
        assertThat(decided.getReviewDecisionNotes()).isEqualTo("Approved via chat");

        expectError(mockMvc.perform(post(URL + token).with(asEnvAdmin())), 409);
        expectError(mockMvc.perform(get(URL + token).with(asEnvAdmin())), 409);
        awaitAsync(() -> assertThat(auditLogs.findAll())
                .filteredOn(a -> a.getAction() == AuditAction.CHAT_ACTION_EXECUTED && Boolean.TRUE.equals(a.getSuccess()))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getDetails()).contains("via chat");
                    assertThat(a.getUserId()).isEqualTo(envAdmin.getUserId());
                }));
    }

    @Test
    void badLinksAre400AndChangeNothing() throws Exception {
        String expired = tokens.issue(ChatActionType.APPROVE_ACCESS_REQUEST, env.getEnvironmentId(), request.getRequestId(),
                Duration.ofSeconds(-1));
        expectError(mockMvc.perform(post(URL + expired).with(asAdmin())), 400);
        String token = approveLink();
        expectError(mockMvc.perform(post(URL + token.substring(0, token.length() - 2) + "xx").with(asAdmin())), 400);
        // E24-T05: an extend link for an environment whose lease already ended explains that.
        String lease = tokens.issue(ChatActionType.EXTEND_LEASE, env.getEnvironmentId(), null);
        expectError(mockMvc.perform(post(URL + lease).with(asAdmin())), 409)
                .andExpect(jsonPath("$.message").value("The lease on Payments QA already ended, so there is nothing to extend"));
        assertThat(accessService.getAccessRequest(request.getRequestId()).getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void theNormalPermissionChecksApplyAndARefusalLeavesTheLinkUsable() throws Exception {
        // The requester cannot approve their own request, and a viewer cannot stop an environment.
        String token = approveLink();
        mockMvc.perform(get(URL + token).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + token).with(asUser())).andExpect(status().isForbidden());
        String stop = tokens.issue(ChatActionType.STOP_ENVIRONMENT, env.getEnvironmentId(), null);
        mockMvc.perform(get(URL + stop).with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(post(URL + stop).with(asViewer())).andExpect(status().isForbidden());

        assertThat(tokenRepository.findAll()).allSatisfy(t -> assertThat(t.getUsedAt()).isNull());
        awaitAsync(() -> assertThat(auditLogs.findAll())
                .filteredOn(a -> a.getAction() == AuditAction.CHAT_ACTION_EXECUTED)
                .hasSize(2)
                .allSatisfy(a -> assertThat(a.getSuccess()).isFalse()));

        // Still usable by someone who may approve.
        mockMvc.perform(post(URL + token).with(asAdmin())).andExpect(status().isOk());
    }

    @Test
    void postNeedsTheCsrfToken() throws Exception {
        String token = approveLink();
        mockMvc.perform(post(URL + token).with(asWithoutCsrf(envAdmin))).andExpect(status().isForbidden());
        assertThat(accessService.getAccessRequest(request.getRequestId()).getStatus().name()).isEqualTo("PENDING");
    }
}
