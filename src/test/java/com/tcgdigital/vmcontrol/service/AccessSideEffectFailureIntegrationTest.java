package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * A failing notification never rolls back the approval or revoke that triggered it (E04-T04,
 * LOW-ACC-2). Not @Transactional, so the commit is observable.
 */
class AccessSideEffectFailureIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EnvironmentAccessService accessService;
    @Autowired private EnvironmentAccessRequestRepository requestRepository;
    @MockitoSpyBean private NotificationService notificationService;

    @Test
    void approvalCommitsWhenTheApprovedNotificationFails() {
        doThrow(new IllegalStateException("mail server down"))
                .when(notificationService).notifyAccessRequestApproved(any(), any(), any());
        Environment env = newEnvironment("Notify");
        User requester = newUser("notify-requester@example.com", false, false);
        User reviewer = newUser("notify-reviewer@example.com", true, false);
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for the release");
        String requestId = accessService.createAccessRequest(env.getEnvironmentId(), requester.getUserId(), dto)
                .getRequestId();

        EnvironmentAccess grant = accessService.approveRequest(requestId, reviewer.getUserId(), null, null);

        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(environmentAccessRepository.findById(grant.getAccessId()).orElseThrow().getStatus())
                .isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void revokeCommitsWhenTheRevokedNotificationFails() {
        doThrow(new IllegalStateException("mail server down"))
                .when(notificationService).notifyAccessRevoked(any(), any(), any());
        Environment env = newEnvironment("Revoke");
        User user = newUser("revoke-user@example.com", false, false);
        User admin = newUser("revoke-admin@example.com", true, false);
        EnvironmentAccess grant = grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);

        accessService.revokeGrantById(admin.getUserId(), grant.getAccessId());

        assertThat(environmentAccessRepository.findById(grant.getAccessId()).orElseThrow().getStatus())
                .isEqualTo(AccessStatus.REVOKED);
    }
}
