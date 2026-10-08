package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request review is race-free and its notifications are written after commit (E04-T04,
 * LOW-ACC-1). Not @Transactional: each call commits on its own, as in production.
 */
class AccessReviewConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EnvironmentAccessService accessService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;

    private EnvironmentAccessRequest pendingRequest(Environment env, User requester) {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for the release");
        return accessService.createAccessRequest(env.getEnvironmentId(), requester.getUserId(), dto);
    }

    private long notificationsOfType(User user, NotificationType type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getUserId(), PageRequest.of(0, 50))
                .stream().filter(n -> n.getType() == type).count();
    }

    @Test
    void twoReviewersApprovingAtOnceProduceExactlyOneGrant() throws Exception {
        Environment env = newEnvironment("Race");
        User requester = newUser("race-requester@example.com", false, false);
        User reviewerA = newUser("race-reviewer-a@example.com", true, false);
        User reviewerB = newUser("race-reviewer-b@example.com", true, false);
        String requestId = pendingRequest(env, requester).getRequestId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (User reviewer : List.of(reviewerA, reviewerB)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return accessService.approveRequest(requestId, reviewer.getUserId(), null, null);
                    } catch (ConflictException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = Collections.synchronizedList(new ArrayList<>());
            for (Future<Object> f : results) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(o -> o instanceof ConflictException).hasSize(1);
            assertThat(outcomes).filteredOn(o -> !(o instanceof ConflictException)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(environmentAccessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(requester.getUserId()))
                .filteredOn(ea -> ea.getStatus() == AccessStatus.ACTIVE)
                .hasSize(1);
        // reset-test-data.sql empties audit_log before each test, so every ACCESS_GRANTED row is ours.
        assertThat(auditLogRepository.findByActionOrderByCreatedAtDesc("ACCESS_GRANTED", PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(1);
        // Written after commit, in its own transaction, exactly once.
        assertThat(notificationsOfType(requester, NotificationType.ACCESS_REQUEST_APPROVED)).isEqualTo(1);
    }

    @Test
    void approvingADeniedRequestIsAConflict() {
        Environment env = newEnvironment("Denied");
        User requester = newUser("denied-requester@example.com", false, false);
        User reviewer = newUser("denied-reviewer@example.com", true, false);
        String requestId = pendingRequest(env, requester).getRequestId();
        accessService.denyRequest(requestId, reviewer.getUserId(), "Not needed");

        assertThatThrownBy(() -> accessService.approveRequest(requestId, reviewer.getUserId(), null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already reviewed");
        assertThat(environmentAccessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(requester.getUserId())).isEmpty();
        assertThat(notificationsOfType(requester, NotificationType.ACCESS_REQUEST_DENIED)).isEqualTo(1);
    }
}
