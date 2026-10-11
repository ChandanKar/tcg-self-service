package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.repository.ChatActionTokenRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signed one-click links (E24-T04): sign/verify, expiry, tampering, and single use under concurrency. */
class ChatActionTokenServiceTest extends AbstractIntegrationTest {

    @Autowired private ChatActionTokenService tokens;
    @Autowired private ChatActionTokenRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aLinkVerifiesWithItsClaims() {
        String token = tokens.issue(ChatActionType.APPROVE_ACCESS_REQUEST, "env-1", "req-1");
        ChatActionTokenService.Claims claims = tokens.verify(token);
        assertThat(claims.type()).isEqualTo(ChatActionType.APPROVE_ACCESS_REQUEST);
        assertThat(claims.environmentId()).isEqualTo("env-1");
        assertThat(claims.targetId()).isEqualTo("req-1");
        assertThat(repository.findById(claims.jti())).isPresent();
        assertThat(token).matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    }

    @Test
    void expiredTamperedOrForeignLinksAre400() {
        String expired = tokens.issue(ChatActionType.STOP_ENVIRONMENT, "env-1", null, Duration.ofSeconds(-5));
        assertThatThrownBy(() -> tokens.verify(expired)).isInstanceOf(ValidationException.class).hasMessage("This link has expired");

        String token = tokens.issue(ChatActionType.STOP_ENVIRONMENT, "env-1", null);
        String[] parts = token.split("\\.");
        // Flip the payload to a different action: the signature no longer matches.
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[0])).replace("STOP_ENVIRONMENT", "EXTEND_LEASE").getBytes())
                + "." + parts[1];
        for (String bad : List.of(forged, parts[0] + ".AAAA", parts[0], "", "a.b.c", token + "x")) {
            assertThatThrownBy(() -> tokens.verify(bad)).as(bad).isInstanceOf(ValidationException.class);
        }

        Object target = AopTestUtils.getTargetObject(tokens);
        Object key = ReflectionTestUtils.getField(target, "signingKey");
        ReflectionTestUtils.setField(target, "signingKey", "another-signing-key-that-is-long-enough-123");
        try {
            assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(ValidationException.class);
        } finally {
            ReflectionTestUtils.setField(target, "signingKey", key);
        }
        assertThat(tokens.verify(token).type()).isEqualTo(ChatActionType.STOP_ENVIRONMENT);
    }

    @Test
    void twoConcurrentUsesLetExactlyOneThrough() throws Exception {
        String token = tokens.issue(ChatActionType.APPROVE_ACCESS_REQUEST, "env-1", "req-1");
        String jti = tokens.verify(token).jti();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        for (String user : List.of("user-a", "user-b")) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    tx.executeWithoutResult(s -> tokens.markUsed(jti, user));
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> r : results) {
            wins += r.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(ConflictException.class);
    }

    @Test
    void chatOnWithoutAKeyFailsStartup() {
        ChatActionTokenService service = new ChatActionTokenService(repository, new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(service, "signingKey", "short");
        ReflectionTestUtils.setField(service, "chatEnabled", true);
        assertThatThrownBy(service::checkKey).isInstanceOf(IllegalStateException.class).hasMessageContaining("chat.action-signing-key");
    }
}
