package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-row access expiry (E04-T01, C4). Not @Transactional: each grant is expired in its own
 * REQUIRES_NEW transaction, which cannot see uncommitted test data. reset-test-data.sql cleans up.
 */
class AccessExpiryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EnvironmentAccessService accessService;

    @Autowired
    private AccessExpiryProcessor processor;

    private EnvironmentAccess grantExpiring(User user, AccessScopeType scope, String scopeId, Instant expiresAt) {
        EnvironmentAccess access = grant(user, scope, scopeId, AccessLevel.USER);
        access.setExpiresAt(Timestamp.from(expiresAt));
        return environmentAccessRepository.saveAndFlush(access);
    }

    private AccessStatus statusOf(EnvironmentAccess access) {
        return environmentAccessRepository.findById(access.getAccessId()).orElseThrow().getStatus();
    }

    @Test
    void expiresEveryPastDueGrantIncludingTwoInTheSameEnvironment() {
        Environment env = newEnvironment("Expiry");
        VmGroup group = newGroup(env, "web");
        User user = newUser("expiry-user@example.com", false, false);
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        // Same (environment, user) twice: the dropped V1 unique index made the second EXPIRED fail.
        EnvironmentAccess envGrant = grantExpiring(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), past);
        EnvironmentAccess groupGrant = grantExpiring(user, AccessScopeType.GROUP, group.getGroupId(), past);

        int expired = accessService.processExpiredAccess();

        assertThat(expired).isEqualTo(2);
        assertThat(statusOf(envGrant)).isEqualTo(AccessStatus.EXPIRED);
        assertThat(statusOf(groupGrant)).isEqualTo(AccessStatus.EXPIRED);
    }

    @Test
    void leavesAGrantExtendedAfterTheIdsWereListed() {
        Environment env = newEnvironment("Extended");
        User user = newUser("extended-user@example.com", false, false);
        EnvironmentAccess extended = grantExpiring(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(),
                Instant.now().plus(7, ChronoUnit.DAYS));

        assertThat(processor.expireOne(extended.getAccessId())).isFalse();
        assertThat(statusOf(extended)).isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void leavesGrantsThatHaveNotExpired() {
        Environment env = newEnvironment("Future");
        User user = newUser("future-user@example.com", false, false);
        EnvironmentAccess future = grantExpiring(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(),
                Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(accessService.processExpiredAccess()).isZero();
        assertThat(statusOf(future)).isEqualTo(AccessStatus.ACTIVE);
    }
}
