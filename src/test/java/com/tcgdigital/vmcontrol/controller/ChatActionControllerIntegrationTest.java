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
 * POST needs CSRF. U09-T08: the preview asks a question and names its source; a link that cannot
 * be used says why in {@code code} (LINK_USED, LINK_EXPIRED, LINK_INVALID, STATE, NOT_ALLOWED),
 * names the environment to people who can see it, and an admin to ask.
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
                .andExpect(jsonPath("$.confirmLabel").value("Approve"))
                .andExpect(jsonPath("$.title").value("Approve this access request?"))
                .andExpect(jsonPath("$.source").value("From the Payments QA Teams channel"));

        mockMvc.perform(post(URL + token).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Approved operator's request on Payments QA."));
        EnvironmentAccessRequest decided = accessService.getAccessRequest(request.getRequestId());
        assertThat(decided.getStatus().name()).isEqualTo("APPROVED");
        assertThat(decided.getReviewDecisionNotes()).isEqualTo("Approved via chat");

        expectError(mockMvc.perform(post(URL + token).with(asEnvAdmin())), 409)
                .andExpect(jsonPath("$.code").value("LINK_USED"));
        // U09-T08: a used link still names its environment, so the page can link to it.
        expectError(mockMvc.perform(get(URL + token).with(asAdmin())), 409)
                .andExpect(jsonPath("$.code").value("LINK_USED"))
                .andExpect(jsonPath("$.environmentId").value(env.getEnvironmentId()))
                .andExpect(jsonPath("$.environmentName").value("Payments QA"));
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
        expectError(mockMvc.perform(post(URL + expired).with(asAdmin())), 400)
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"))
                .andExpect(jsonPath("$.message").value("This link has expired"))
                .andExpect(jsonPath("$.environmentName").value("Payments QA"));
        String token = approveLink();
        expectError(mockMvc.perform(post(URL + token.substring(0, token.length() - 2) + "xx").with(asAdmin())), 400)
                .andExpect(jsonPath("$.code").value("LINK_INVALID"))
                .andExpect(jsonPath("$.environmentId").doesNotExist());
        // E24-T05: an extend link for an environment whose lease already ended explains that.
        String lease = tokens.issue(ChatActionType.EXTEND_LEASE, env.getEnvironmentId(), null);
        expectError(mockMvc.perform(post(URL + lease).with(asAdmin())), 409)
                .andExpect(jsonPath("$.message").value("The lease on Payments QA already ended, so there is nothing to extend"))
                .andExpect(jsonPath("$.code").value("STATE"));
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
    void aViewerIsToldWhatTheyCanDoAndWhoToAsk() throws Exception {
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        grantEnv(envAdmin, env.getEnvironmentId(), AccessLevel.ADMIN);
        String extend = tokens.issue(ChatActionType.EXTEND_LEASE, env.getEnvironmentId(), null);
        String adminName = envAdmin.getDisplayName() != null ? envAdmin.getDisplayName() : envAdmin.getEmail();

        expectError(mockMvc.perform(get(URL + extend).with(asViewer())), 403)
                .andExpect(jsonPath("$.code").value("NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("You can view Payments QA but not change its lease. Ask its admin, " + adminName + "."))
                .andExpect(jsonPath("$.adminName").value(adminName))
                .andExpect(jsonPath("$.environmentId").value(env.getEnvironmentId()));
    }

    @Test
    void someoneWithoutAccessIsNotToldTheEnvironmentOrItsAdmins() throws Exception {
        String stop = tokens.issue(ChatActionType.STOP_ENVIRONMENT, env.getEnvironmentId(), null);
        expectError(mockMvc.perform(get(URL + stop).with(asViewer())), 403)
                .andExpect(jsonPath("$.code").value("NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("You don't have access to this environment. Ask for it on Request access."))
                .andExpect(jsonPath("$.environmentName").doesNotExist())
                .andExpect(jsonPath("$.adminName").doesNotExist());
    }

    @Test
    void theStopPreviewAsksAQuestion() throws Exception {
        String stop = tokens.issue(ChatActionType.STOP_ENVIRONMENT, env.getEnvironmentId(), null);
        mockMvc.perform(get(URL + stop).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Stop Payments QA?"))
                .andExpect(jsonPath("$.source").value("From the Payments QA Teams channel"))
                .andExpect(jsonPath("$.description").value("Every running VM in Payments QA stops, in reverse start order."));
    }

    @Test
    void postNeedsTheCsrfToken() throws Exception {
        String token = approveLink();
        mockMvc.perform(post(URL + token).with(asWithoutCsrf(envAdmin))).andExpect(status().isForbidden());
        assertThat(accessService.getAccessRequest(request.getRequestId()).getStatus().name()).isEqualTo("PENDING");
    }
}
