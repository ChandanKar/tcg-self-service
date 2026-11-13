package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V102 (U23-T08): the unread state-change notices in the pre-U22-T12 format ('State drift detected ...')
 * are marked read; the new 'Changed outside the platform: <env>' notices and other types stay unread.
 * The migration has run once at startup; the test runs its statement again (twice) on rows of its own.
 */
class OldDriftNoticesMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String notice(String type, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notification (notification_id, user_id, type, title, message, is_read, created_at) "
                + "VALUES (?, ?, ?, ?, 'm', FALSE, CURRENT_TIMESTAMP)", id, "user-x", type, title);
        return id;
    }

    private boolean isRead(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_read FROM notification WHERE notification_id = ?",
                Boolean.class, id));
    }

    @Test
    void onlyOldFormatDriftNoticesAreMarkedRead() throws Exception {
        String old = notice("STATE_DRIFT_DETECTED", "State drift detected for \"web-01\": STOPPED -> RUNNING");
        String fresh = notice("STATE_DRIFT_DETECTED", "Changed outside the platform: ise");
        String otherType = notice("OPERATION_COMPLETED", "State drift detected lookalike");

        String sql = new String(new ClassPathResource("db/migration/V102__mark_old_drift_notices_read.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        jdbc.execute(sql);
        jdbc.execute(sql); // idempotent

        assertThat(isRead(old)).isTrue();
        assertThat(isRead(fresh)).isFalse();
        assertThat(isRead(otherType)).isFalse();
    }
}
