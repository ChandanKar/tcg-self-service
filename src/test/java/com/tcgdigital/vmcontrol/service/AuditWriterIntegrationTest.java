package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit rows follow the caller's transaction (E11-T01, H9): a success row is written only if
 * the caller commits; a failure row is written even if the caller rolls back; a "system" actor
 * is stored as NULL rather than breaking the user FK; environment changes record the real actor.
 * Against MySQL.
 */
class AuditWriterIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private EnvironmentService environmentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private int rows(String targetId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = ?", Integer.class, targetId);
        return n == null ? 0 : n;
    }

    private void inTransactionThenFail(Runnable work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            work.run();
            throw new IllegalStateException("caller fails");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuccessRowRollsBackWithItsCaller() {
        String target = UUID.randomUUID().toString();
        inTransactionThenFail(() -> auditService.logEnvironmentAction(null, AuditAction.ENVIRONMENT_UPDATED,
                null, null, "test", target, "t", "should not survive"));

        Awaitility.await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(rows(target)).isZero());
    }

    @Test
    void aFailureRowSurvivesItsCallersRollback() {
        String target = UUID.randomUUID().toString();
        inTransactionThenFail(() -> auditService.logEnvironmentFailure(null, AuditAction.ENVIRONMENT_UPDATED,
                null, null, "test", target, "t", "boom"));

        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT action_status FROM audit_log WHERE target_id = ?", String.class, target)).isEqualTo("failed"));
    }

    @Test
    void aCommittedCallerKeepsItsSuccessRow() {
        String target = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                auditService.logAction(null, AuditAction.ENVIRONMENT_UPDATED, "test", target, "t", "kept"));

        awaitAsync(() -> assertThat(rows(target)).isEqualTo(1));
    }

    @Test
    void aSystemActorIsStoredAsNull() {
        String target = UUID.randomUUID().toString();
        auditService.logAction("system", AuditAction.ENVIRONMENT_UPDATED, "test", target, "t", "by the system");

        awaitAsync(() -> {
            assertThat(rows(target)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM audit_log WHERE target_id = ?", String.class, target)).isNull();
        });
    }

    @Test
    void environmentChangesRecordTheRealActor() {
        User admin = newUser("audit-admin-" + UUID.randomUUID() + "@example.com", true, false);
        Environment env = newEnvironment("Actor");

        environmentService.deactivateEnvironment(env.getEnvironmentId(), admin.getUserId());
        environmentService.reactivateEnvironment(env.getEnvironmentId(), admin.getUserId());

        awaitAsync(() -> {
            assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM audit_log WHERE action_type = 'ENVIRONMENT_DEACTIVATED' "
                    + "AND environment_id = ?", String.class, env.getEnvironmentId())).isEqualTo(admin.getUserId());
            assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM audit_log WHERE action_type = 'ENVIRONMENT_ACTIVATED' "
                    + "AND environment_id = ?", String.class, env.getEnvironmentId())).isEqualTo(admin.getUserId());
        });
    }

    // ---- Actor snapshot (E11-T02) ----

    @Test
    void aDeletedUsersRowsStillShowTheirNameAndEmail() {
        User gone = newUser("leaver-" + UUID.randomUUID() + "@example.com", false, false);
        String target = UUID.randomUUID().toString();
        auditService.logAction(gone.getUserId(), AuditAction.ENVIRONMENT_UPDATED, "test", target, "t", "before leaving");
        awaitAsync(() -> assertThat(rows(target)).isEqualTo(1));
        assertThat(jdbcTemplate.queryForObject("SELECT actor_email FROM audit_log WHERE target_id = ?", String.class, target))
                .isEqualTo(gone.getEmail());

        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", gone.getUserId());

        var entry = auditService.getRecentLogs().stream().filter(l -> target.equals(l.getTargetId())).findFirst().orElseThrow();
        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getUserDisplayName()).isEqualTo(gone.getDisplayName());
        assertThat(entry.getUserEmail()).isEqualTo(gone.getEmail());
    }
}
