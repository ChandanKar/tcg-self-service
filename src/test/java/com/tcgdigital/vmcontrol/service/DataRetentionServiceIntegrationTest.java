package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.scheduler.DataRetentionScheduler;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention deletes only rows past each table's period (E11-T07, M37), keeps unread
 * notifications longer than read ones, works through a backlog in batches, and does not run
 * while retention.enabled is off. Against MySQL.
 */
class DataRetentionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DataRetentionService retentionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext context;

    private User user;
    private Vm vm;

    @BeforeEach
    void setUp() {
        user = newUser("retention-" + UUID.randomUUID() + "@example.com", false, false);
        Environment env = newEnvironment("Retention");
        vm = newVm(newGroup(env, "app"), "vm", VmStatus.STOPPED);
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }

    private String audit(int daysAgo) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO audit_log (audit_id, action_type, target_type, target_id, action_status, created_at) "
                + "VALUES (?, 'ENVIRONMENT_UPDATED', 'test', 't', 'succeeded', ?)", id, daysAgo(daysAgo));
        return id;
    }

    private String notification(int daysAgo, boolean read) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO notification (notification_id, user_id, type, title, message, is_read, created_at) "
                + "VALUES (?, ?, 'LOCK_RELEASED', 't', 'm', ?, ?)", id, user.getUserId(), read, daysAgo(daysAgo));
        return id;
    }

    private String stateChange(int daysAgo) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO vm_state_history (history_id, vm_id, new_status, change_source, created_at) "
                + "VALUES (?, ?, 'RUNNING', 'system', ?)", id, vm.getVmId(), daysAgo(daysAgo));
        return id;
    }

    private String email(int daysAgo) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO email_log (log_id, recipients, recipient_count, subject, success, sent_at) "
                + "VALUES (?, 'a@example.com', 1, 's', TRUE, ?)", id, daysAgo(daysAgo));
        return id;
    }

    private boolean exists(String table, String idColumn, String id) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?", Integer.class, id);
        return n != null && n > 0;
    }

    @Test
    void onlyRowsPastEachTablesPeriodAreDeleted() {
        String oldAudit = audit(400), newAudit = audit(10);
        String oldRead = notification(100, true), newRead = notification(10, true);
        String oldUnread = notification(200, false), midUnread = notification(100, false);
        String oldState = stateChange(200), newState = stateChange(10);
        String oldEmail = email(200), newEmail = email(10);

        retentionService.purgeAll();

        assertThat(exists("audit_log", "audit_id", oldAudit)).isFalse();
        assertThat(exists("audit_log", "audit_id", newAudit)).isTrue();
        assertThat(exists("notification", "notification_id", oldRead)).isFalse();
        assertThat(exists("notification", "notification_id", newRead)).isTrue();
        assertThat(exists("notification", "notification_id", oldUnread)).isFalse();
        // 100 days: past the read period, but an unread notification is kept for 180.
        assertThat(exists("notification", "notification_id", midUnread)).isTrue();
        assertThat(exists("vm_state_history", "history_id", oldState)).isFalse();
        assertThat(exists("vm_state_history", "history_id", newState)).isTrue();
        assertThat(exists("email_log", "log_id", oldEmail)).isFalse();
        assertThat(exists("email_log", "log_id", newEmail)).isTrue();
    }

    @Test
    void aBacklogIsDeletedInBatches() {
        ReflectionTestUtils.setField(retentionService, "batchSize", 5);
        try {
            for (int i = 0; i < 12; i++) {
                notification(120, true);
            }

            assertThat(retentionService.purgeAll().get("notification_read")).isGreaterThanOrEqualTo(12);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Integer.class,
                    user.getUserId())).isZero();
        } finally {
            ReflectionTestUtils.setField(retentionService, "batchSize", 5000);
        }
    }

    @Test
    void theSchedulerIsAbsentWhileRetentionIsOff() {
        assertThat(context.getBeansOfType(DataRetentionScheduler.class)).isEmpty();
    }
}
