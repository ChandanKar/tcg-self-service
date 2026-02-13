package com.tcgdigital.vmcontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setupTestData() {
        // Clean up any existing test data
        jdbcTemplate.update("DELETE FROM environment_access WHERE user_id IN (SELECT user_id FROM app_user WHERE email LIKE 'test%@example.com')");
        jdbcTemplate.update("DELETE FROM app_user WHERE email LIKE 'test%@example.com'");
        jdbcTemplate.update("DELETE FROM environment WHERE name LIKE 'test-%'");
    }

    // =====================================================
    // BASIC SCHEMA TESTS
    // =====================================================

    @Test
    void testFlywayMigrationApplied() {
        // Verify flyway_schema_history table exists and has V1 migration
        // Use quoted identifier for H2 case sensitivity
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1'",
            Integer.class
        );
        assertNotNull(count);
        assertEquals(1, count, "V1 migration should be applied");
    }

    @Test
    void testAppUserTableCreated() {
        Integer columnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'APP_USER'",
            Integer.class
        );
        assertNotNull(columnCount);
        assertTrue(columnCount > 0, "app_user table should have columns");
    }

    @Test
    void testAuditLogTableCreated() {
        Integer columnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AUDIT_LOG'",
            Integer.class
        );
        assertNotNull(columnCount);
        assertTrue(columnCount > 0, "audit_log table should have columns");
    }

    @Test
    void testEnvironmentTableCreated() {
        Integer columnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'ENVIRONMENT'",
            Integer.class
        );
        assertNotNull(columnCount);
        assertTrue(columnCount > 0, "environment table should have columns");
    }

    @Test
    void testVmTableCreated() {
        Integer columnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'VM'",
            Integer.class
        );
        assertNotNull(columnCount);
        assertTrue(columnCount > 0, "vm table should have columns");
    }

    @Test
    void testCanInsertAndQueryUser() {
        String testUserId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id) VALUES (?, ?, ?, ?)",
            testUserId, "test@example.com", "Test User", "oid-123"
        );

        String displayName = jdbcTemplate.queryForObject(
            "SELECT display_name FROM app_user WHERE email = ?",
            String.class,
            "test@example.com"
        );
        assertEquals("Test User", displayName);

        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", testUserId);
    }

    @Test
    void testSystemConfigSeeded() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM system_config",
            Integer.class
        );
        assertNotNull(count);
        assertTrue(count >= 6, "system_config should have seed data");
    }

    @Test
    void testScheduledJobsSeeded() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM scheduled_job",
            Integer.class
        );
        assertNotNull(count);
        assertTrue(count >= 4, "scheduled_job should have seed data");
    }

    // =====================================================
    // USEFUL QUERIES TESTS (adapted from useful-queries.sql)
    // =====================================================

    @Test
    void testQueryFindAllGlobalAdmins() {
        // Setup: create admin user
        String adminId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id, admin) VALUES (?, ?, ?, ?, ?)",
            adminId, "testadmin@example.com", "Test Admin", "oid-admin-1", true
        );

        // Query: Find all global admins
        List<Map<String, Object>> admins = jdbcTemplate.queryForList(
            "SELECT user_id, email, display_name, last_login_at " +
            "FROM app_user WHERE admin = TRUE ORDER BY display_name"
        );

        assertNotNull(admins);
        assertTrue(admins.size() >= 1, "Should find at least one admin");
        assertTrue(admins.stream().anyMatch(a -> "testadmin@example.com".equals(a.get("EMAIL"))));

        // Cleanup
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", adminId);
    }

    @Test
    void testQueryFindAllEnvironmentAdmins() {
        // Setup: create env admin user
        String envAdminId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id, env_admin) VALUES (?, ?, ?, ?, ?)",
            envAdminId, "testenvadmin@example.com", "Test Env Admin", "oid-envadmin-1", true
        );

        // Query: Find all environment admins
        List<Map<String, Object>> envAdmins = jdbcTemplate.queryForList(
            "SELECT DISTINCT user_id, email, display_name FROM app_user WHERE env_admin = TRUE ORDER BY display_name"
        );

        assertNotNull(envAdmins);
        assertTrue(envAdmins.size() >= 1, "Should find at least one env admin");

        // Cleanup
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", envAdminId);
    }

    @Test
    void testQueryEnvironmentOverviewWithVmCounts() {
        // Setup: create environment
        String envId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name, is_active) VALUES (?, ?, ?, ?)",
            envId, "test-env-overview", "Test Environment Overview", true
        );

        // Query: Get environment overview with VM counts
        List<Map<String, Object>> overview = jdbcTemplate.queryForList(
            "SELECT e.environment_id, e.name, e.display_name, " +
            "COUNT(DISTINCT g.group_id) as group_count, " +
            "COUNT(v.vm_id) as total_vms, " +
            "SUM(CASE WHEN v.status = 'running' THEN 1 ELSE 0 END) as running_vms, " +
            "SUM(CASE WHEN v.status = 'stopped' THEN 1 ELSE 0 END) as stopped_vms " +
            "FROM environment e " +
            "LEFT JOIN vm_group g ON e.environment_id = g.environment_id " +
            "LEFT JOIN vm v ON g.group_id = v.group_id " +
            "WHERE e.is_active = TRUE " +
            "GROUP BY e.environment_id, e.name, e.display_name " +
            "ORDER BY e.name"
        );

        assertNotNull(overview);
        assertTrue(overview.size() >= 1, "Should return at least one environment");

        // Cleanup
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
    }

    @Test
    void testQueryEnvironmentAccessForUser() {
        // Setup: create user, environment, and access
        String userId = UUID.randomUUID().toString();
        String envId = UUID.randomUUID().toString();
        String accessId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id) VALUES (?, ?, ?, ?)",
            userId, "testaccess@example.com", "Test Access User", "oid-access-1"
        );
        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name) VALUES (?, ?, ?)",
            envId, "test-env-access", "Test Environment Access"
        );
        jdbcTemplate.update(
            "INSERT INTO environment_access (access_id, environment_id, user_id, access_level, granted_by_user_id, status) VALUES (?, ?, ?, ?, ?, ?)",
            accessId, envId, userId, "admin", userId, "active"
        );

        // Query: Get all environments a user can access
        List<Map<String, Object>> userEnvs = jdbcTemplate.queryForList(
            "SELECT e.environment_id, e.name, e.display_name, ea.access_level " +
            "FROM environment e " +
            "JOIN environment_access ea ON e.environment_id = ea.environment_id " +
            "WHERE ea.user_id = ? " +
            "AND ea.status = 'active' " +
            "AND (ea.expires_at IS NULL OR ea.expires_at > CURRENT_TIMESTAMP) " +
            "ORDER BY e.name",
            userId
        );

        assertNotNull(userEnvs);
        assertEquals(1, userEnvs.size(), "User should have access to one environment");
        assertEquals("admin", userEnvs.get(0).get("ACCESS_LEVEL"));

        // Cleanup
        jdbcTemplate.update("DELETE FROM environment_access WHERE access_id = ?", accessId);
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", userId);
    }

    @Test
    void testQueryVmGroupsAndVmsInEnvironment() {
        // Setup: create environment, group, and VM
        String envId = UUID.randomUUID().toString();
        String groupId = UUID.randomUUID().toString();
        String vmId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name) VALUES (?, ?, ?)",
            envId, "test-env-vms", "Test Environment VMs"
        );
        jdbcTemplate.update(
            "INSERT INTO vm_group (group_id, environment_id, name, display_name, sequence_position) VALUES (?, ?, ?, ?, ?)",
            groupId, envId, "test-group", "Test Group", 1
        );
        jdbcTemplate.update(
            "INSERT INTO vm (vm_id, group_id, name, display_name, provider, region, provider_vm_id, sequence_position, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            vmId, groupId, "test-vm", "Test VM", "AWS", "us-east-1", "i-12345", 1, "stopped"
        );

        // Query: Get all VMs in an environment with group info
        List<Map<String, Object>> vms = jdbcTemplate.queryForList(
            "SELECT g.name as group_name, g.sequence_position as group_sequence, " +
            "v.vm_id, v.name as vm_name, v.provider, v.region, v.status, v.sequence_position as vm_sequence " +
            "FROM vm_group g " +
            "JOIN vm v ON g.group_id = v.group_id " +
            "WHERE g.environment_id = ? " +
            "ORDER BY g.sequence_position, v.sequence_position",
            envId
        );

        assertNotNull(vms);
        assertEquals(1, vms.size(), "Should find one VM");
        assertEquals("test-vm", vms.get(0).get("VM_NAME"));
        assertEquals("AWS", vms.get(0).get("PROVIDER"));

        // Cleanup
        jdbcTemplate.update("DELETE FROM vm WHERE vm_id = ?", vmId);
        jdbcTemplate.update("DELETE FROM vm_group WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
    }

    @Test
    void testQueryActiveEnvironmentLocks() {
        // Setup: create user, environment, and lock
        String userId = UUID.randomUUID().toString();
        String envId = UUID.randomUUID().toString();
        String lockId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id) VALUES (?, ?, ?, ?)",
            userId, "testlock@example.com", "Test Lock User", "oid-lock-1"
        );
        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name) VALUES (?, ?, ?)",
            envId, "test-env-lock", "Test Environment Lock"
        );
        jdbcTemplate.update(
            "INSERT INTO environment_lock (lock_id, environment_id, locked_by_user_id, lock_reason, is_active) VALUES (?, ?, ?, ?, ?)",
            lockId, envId, userId, "Testing lock functionality", true
        );

        // Query: Find all active locks
        List<Map<String, Object>> locks = jdbcTemplate.queryForList(
            "SELECT el.lock_id, e.name as environment_name, u.display_name as locked_by, " +
            "u.email as locked_by_email, el.locked_at, el.lock_reason " +
            "FROM environment_lock el " +
            "JOIN environment e ON el.environment_id = e.environment_id " +
            "JOIN app_user u ON el.locked_by_user_id = u.user_id " +
            "WHERE el.is_active = TRUE " +
            "ORDER BY el.locked_at ASC"
        );

        assertNotNull(locks);
        assertTrue(locks.size() >= 1, "Should find at least one active lock");

        // Cleanup
        jdbcTemplate.update("DELETE FROM environment_lock WHERE lock_id = ?", lockId);
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", userId);
    }

    @Test
    void testQueryUnreadNotificationsForUser() {
        // Setup: create user and notification
        String userId = UUID.randomUUID().toString();
        String notificationId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id) VALUES (?, ?, ?, ?)",
            userId, "testnotif@example.com", "Test Notification User", "oid-notif-1"
        );
        jdbcTemplate.update(
            "INSERT INTO notification (notification_id, user_id, notification_type, title, message, severity, is_read, is_dismissed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            notificationId, userId, "test_notification", "Test Title", "Test Message", "info", false, false
        );

        // Query: Get unread notifications for a user
        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(
            "SELECT n.notification_id, n.notification_type, n.title, n.message, n.severity, n.created_at " +
            "FROM notification n " +
            "WHERE n.user_id = ? AND n.is_read = FALSE AND n.is_dismissed = FALSE " +
            "ORDER BY n.created_at DESC",
            userId
        );

        assertNotNull(notifications);
        assertEquals(1, notifications.size(), "Should find one unread notification");
        assertEquals("Test Title", notifications.get(0).get("TITLE"));

        // Cleanup
        jdbcTemplate.update("DELETE FROM notification WHERE notification_id = ?", notificationId);
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", userId);
    }

    @Test
    void testQueryAuditLogForEnvironment() {
        // Setup: create user, environment, and audit entry
        String userId = UUID.randomUUID().toString();
        String envId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO app_user (user_id, email, display_name, azure_ad_object_id) VALUES (?, ?, ?, ?)",
            userId, "testaudit@example.com", "Test Audit User", "oid-audit-1"
        );
        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name) VALUES (?, ?, ?)",
            envId, "test-env-audit", "Test Environment Audit"
        );
        jdbcTemplate.update(
            "INSERT INTO audit_log (audit_id, user_id, environment_id, action_type, target_type, target_name, action_status) VALUES (?, ?, ?, ?, ?, ?, ?)",
            auditId, userId, envId, "vm_start", "vm", "test-vm", "succeeded"
        );

        // Query: Recent audit log for an environment
        List<Map<String, Object>> auditLogs = jdbcTemplate.queryForList(
            "SELECT al.audit_id, u.display_name as user_name, al.action_type, al.target_type, " +
            "al.target_name, al.action_status, al.created_at " +
            "FROM audit_log al " +
            "LEFT JOIN app_user u ON al.user_id = u.user_id " +
            "WHERE al.environment_id = ? " +
            "ORDER BY al.created_at DESC",
            envId
        );

        assertNotNull(auditLogs);
        assertEquals(1, auditLogs.size(), "Should find one audit entry");
        assertEquals("vm_start", auditLogs.get(0).get("ACTION_TYPE"));

        // Cleanup
        jdbcTemplate.update("DELETE FROM audit_log WHERE audit_id = ?", auditId);
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", userId);
    }

    @Test
    void testQueryVmStateHistory() {
        // Setup: create environment, group, VM, and state history
        String envId = UUID.randomUUID().toString();
        String groupId = UUID.randomUUID().toString();
        String vmId = UUID.randomUUID().toString();
        String historyId = UUID.randomUUID().toString();

        jdbcTemplate.update(
            "INSERT INTO environment (environment_id, name, display_name) VALUES (?, ?, ?)",
            envId, "test-env-history", "Test Environment History"
        );
        jdbcTemplate.update(
            "INSERT INTO vm_group (group_id, environment_id, name, display_name, sequence_position) VALUES (?, ?, ?, ?, ?)",
            groupId, envId, "test-group-history", "Test Group History", 1
        );
        jdbcTemplate.update(
            "INSERT INTO vm (vm_id, group_id, name, display_name, provider, region, provider_vm_id, sequence_position, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            vmId, groupId, "test-vm-history", "Test VM History", "AWS", "us-east-1", "i-history", 1, "running"
        );
        jdbcTemplate.update(
            "INSERT INTO vm_state_history (history_id, vm_id, previous_status, new_status, change_source) VALUES (?, ?, ?, ?, ?)",
            historyId, vmId, "stopped", "running", "user_action"
        );

        // Query: VM state change history
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
            "SELECT vsh.created_at, vsh.previous_status, vsh.new_status, vsh.change_source " +
            "FROM vm_state_history vsh " +
            "WHERE vsh.vm_id = ? " +
            "ORDER BY vsh.created_at DESC",
            vmId
        );

        assertNotNull(history);
        assertEquals(1, history.size(), "Should find one state change");
        assertEquals("stopped", history.get(0).get("PREVIOUS_STATUS"));
        assertEquals("running", history.get(0).get("NEW_STATUS"));

        // Cleanup
        jdbcTemplate.update("DELETE FROM vm_state_history WHERE history_id = ?", historyId);
        jdbcTemplate.update("DELETE FROM vm WHERE vm_id = ?", vmId);
        jdbcTemplate.update("DELETE FROM vm_group WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM environment WHERE environment_id = ?", envId);
    }
}
