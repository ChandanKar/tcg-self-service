package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expiry warnings are de-duplicated per expiry, not per grant: an extension moves the expiry,
 * so the next warning window warns again (E04-T05, M19). Not @Transactional: the warnings are
 * written after commit. Default warning window: 1 day.
 */
class AccessExpiryWarningIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EnvironmentAccessService accessService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private EnvironmentAccess grant;

    @BeforeEach
    void grantExpiringInTwelveHours() {
        Environment env = newEnvironment("Warn");
        user = newUser("warn-user@example.com", false, false);
        grant = grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        setExpiry(Instant.now().plus(12, ChronoUnit.HOURS));
    }

    private void setExpiry(Instant expiresAt) {
        grant.setExpiresAt(Timestamp.from(expiresAt));
        grant = environmentAccessRepository.saveAndFlush(grant);
    }

    private long warnings() {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getUserId(), PageRequest.of(0, 20))
                .stream().filter(n -> n.getType() == NotificationType.ACCESS_EXPIRING).count();
    }

    @Test
    void warnsOnceWhenTheJobRunsTwiceInTheSameWindow() {
        accessService.processExpiringAccessWarnings();
        accessService.processExpiringAccessWarnings();

        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    void warnsAgainBeforeTheNewExpiryAfterAnExtension() {
        accessService.processExpiringAccessWarnings();
        assertThat(warnings()).isEqualTo(1);

        // A week passes: the grant was extended by 7 days and is now inside its new window.
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS)), user.getUserId());
        setExpiry(Instant.now().plus(12, ChronoUnit.HOURS));

        accessService.processExpiringAccessWarnings();

        assertThat(warnings()).isEqualTo(2);
    }

    @Test
    void doesNotWarnBeforeTheWindowOpens() {
        setExpiry(Instant.now().plus(3, ChronoUnit.DAYS));

        accessService.processExpiringAccessWarnings();

        assertThat(warnings()).isZero();
    }
}
