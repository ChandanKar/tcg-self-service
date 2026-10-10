package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks do not outlive their holder's right to hold them (E07-T04, H4): deactivation releases all
 * of a user's locks; a revoked, expired or lowered grant releases the lock unless the holder can
 * still operate there through another grant. Against MySQL; not @Transactional.
 */
class LockAutoReleaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private UserService userService;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private AccessExpiryProcessor accessExpiryProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User admin;
    private User bob;
    private User colleague;

    @BeforeEach
    void setUp() {
        admin = newUser("auto-admin-" + UUID.randomUUID() + "@example.com", true, false);
        bob = newUser("auto-bob-" + UUID.randomUUID() + "@example.com", false, false);
        colleague = newUser("auto-colleague-" + UUID.randomUUID() + "@example.com", false, false);
    }

    private Environment environment() {
        Environment env = newEnvironment("AutoRelease");
        grant(colleague, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        return env;
    }

    private EnvironmentLock bobLocks(Environment env) {
        return lockService.acquireLock(env.getEnvironmentId(), bob.getUserId(), "deploy", null);
    }

    private boolean active(EnvironmentLock lock) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM environment_lock WHERE lock_id = ?", Boolean.class, lock.getLockId()));
    }

    private String releaseNotes(EnvironmentLock lock) {
        return jdbcTemplate.queryForList("SELECT notes FROM lock_history WHERE lock_id = ? AND action = 'RELEASED' "
                + "AND performed_by_user_id = ?", String.class, lock.getLockId(), bob.getUserId())
                .stream().findFirst().orElse(null);
    }

    @Test
    void deactivatingAHolderReleasesAllTheirLocksAndTellsTheEnvironment() {
        Environment dev = environment();
        Environment qa = environment();
        grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.ENVIRONMENT, qa.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock devLock = bobLocks(dev);
        EnvironmentLock qaLock = bobLocks(qa);

        userService.deactivateUser(bob.getUserId(), admin.getUserId());

        assertThat(active(devLock)).isFalse();
        assertThat(active(qaLock)).isFalse();
        assertThat(releaseNotes(devLock)).isEqualTo("Auto-released: user deactivated");
        awaitAsync(() -> {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? "
                    + "AND type = 'LOCK_RELEASED' AND entity_id = ?", Integer.class, colleague.getUserId(), dev.getEnvironmentId()))
                    .isPositive();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = 'LOCK_RELEASED' "
                    + "AND environment_id = ? AND user_id IS NULL", Integer.class, dev.getEnvironmentId())).isEqualTo(1);
        });
    }

    @Test
    void deactivatingAUserWithoutLocksChangesNothing() {
        assertThat(userService.deactivateUser(bob.getUserId(), admin.getUserId()).getIsActive()).isFalse();
    }

    @Test
    void revokingTheHoldersOnlyGrantReleasesTheLock() {
        Environment dev = environment();
        EnvironmentAccess only = grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);

        accessService.revokeGrantById(admin.getUserId(), only.getAccessId());

        assertThat(active(lock)).isFalse();
        assertThat(releaseNotes(lock)).isEqualTo("Auto-released: access revoked");
    }

    @Test
    void revokingThroughTheEnvironmentEndpointReleasesTheLockToo() {
        Environment dev = environment();
        grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);

        accessService.revokeAccess(dev.getEnvironmentId(), bob.getUserId(), admin.getUserId());

        assertThat(active(lock)).isFalse();
    }

    @Test
    void anExpiredGrantReleasesTheLock() {
        Environment dev = environment();
        EnvironmentAccess only = grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);
        jdbcTemplate.update("UPDATE environment_access SET expires_at = ? WHERE access_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), only.getAccessId());

        assertThat(accessExpiryProcessor.expireOne(only.getAccessId())).isTrue();

        assertThat(active(lock)).isFalse();
        assertThat(releaseNotes(lock)).isEqualTo("Auto-released: access expired");
    }

    @Test
    void loweringTheHolderToViewerReleasesTheLock() {
        Environment dev = environment();
        grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);
        GrantAccessDTO dto = new GrantAccessDTO();
        dto.setUserEmail(bob.getEmail());
        dto.setAccessLevel(AccessLevel.VIEWER);

        accessService.grantAccess(dev.getEnvironmentId(), admin.getUserId(), dto);

        assertThat(active(lock)).isFalse();
        assertThat(releaseNotes(lock)).startsWith("Auto-released: access level changed");
    }

    @Test
    void aHolderWhoCanStillOperateThroughAGroupGrantKeepsTheLock() {
        Environment dev = environment();
        VmGroup web = newGroup(dev, "web");
        EnvironmentAccess envGrant = grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.GROUP, web.getGroupId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);

        accessService.revokeGrantById(admin.getUserId(), envGrant.getAccessId());

        assertThat(active(lock)).isTrue();
    }

    @Test
    void anotherUsersRevokedGrantLeavesTheLockAlone() {
        Environment dev = environment();
        grant(bob, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        User other = newUser("auto-other-" + UUID.randomUUID() + "@example.com", false, false);
        EnvironmentAccess otherGrant = grant(other, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = bobLocks(dev);

        accessService.revokeGrantById(admin.getUserId(), otherGrant.getAccessId());

        assertThat(active(lock)).isTrue();
    }

    @Test
    void aGlobalEnvAdminHolderKeepsTheLockWhenAGrantGoes() {
        User envAdmin = newUser("auto-envadmin-" + UUID.randomUUID() + "@example.com", false, true);
        Environment dev = environment();
        EnvironmentAccess grant = grant(envAdmin, AccessScopeType.ENVIRONMENT, dev.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLock lock = lockService.acquireLock(dev.getEnvironmentId(), envAdmin.getUserId(), "deploy", null);

        accessService.revokeGrantById(admin.getUserId(), grant.getAccessId());

        assertThat(active(lock)).isTrue();
    }
}
