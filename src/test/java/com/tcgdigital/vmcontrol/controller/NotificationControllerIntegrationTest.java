package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Marking a notification read is limited to its owner; anyone else's (or a missing one) is a 404
 * with the standard error body, never a 500 (E11-T08).
 */
class NotificationControllerIntegrationTest extends SecuredWebTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;

    private String notificationFor(String userId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO notification (notification_id, user_id, type, title, message, is_read) "
                + "VALUES (?, ?, 'LOCK_RELEASED', 't', 'm', FALSE)", id, userId);
        return id;
    }

    @Test
    void someoneElsesNotificationIsANotFound() throws Exception {
        String theirs = notificationFor(admin.getUserId());

        expectError(mockMvc.perform(patch("/api/v1/notifications/" + theirs + "/read").with(asUser())), 404);
        assertThat(jdbcTemplate.queryForObject("SELECT is_read FROM notification WHERE notification_id = ?",
                Boolean.class, theirs)).isFalse();
    }

    @Test
    void aMissingNotificationIsANotFound() throws Exception {
        expectError(mockMvc.perform(patch("/api/v1/notifications/" + UUID.randomUUID() + "/read").with(asUser())), 404);
    }

    @Test
    void theOwnerCanMarkTheirNotificationRead() throws Exception {
        String mine = notificationFor(operator.getUserId());

        mockMvc.perform(patch("/api/v1/notifications/" + mine + "/read").with(asUser())).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT is_read FROM notification WHERE notification_id = ?",
                Boolean.class, mine)).isTrue();
    }
}
