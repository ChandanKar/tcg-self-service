package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // Clear audit logs for clean test state
        auditLogRepository.deleteAll();
    }

    @Test
    void testLogLockAcquired_detailsUseUsernameNotRawUserId() {
        User user = User.fromUsernamePassword("audit.test.user", "irrelevant",
                "audit.test.user@tcgdigital.com", "Audit Test User", "TCG");
        User saved = userRepository.save(user);
        Environment env = newEnvironment("Audit Test Environment");

        auditService.logLockAcquired(saved.getUserId(), env.getEnvironmentId(), env.getDisplayName(), "maintenance");

        awaitAsync(() -> {
            AuditLog logEntry = auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(l -> l.getAction() == AuditAction.LOCK_ACQUIRED)
                    .findFirst().orElseThrow();
            assertTrue(logEntry.getDetails().contains("audit.test.user"), "details should contain the username");
            assertFalse(logEntry.getDetails().contains(saved.getUserId()), "details should not contain the raw user id");
        });
    }

    @Test
    void testLogLockReleased_infersUsernameFromEmailWhenUsernameBlank() {
        User user = User.fromAzureAd("audit-azure-oid", "jane.smith@example.com", "Jane Smith");
        User saved = userRepository.save(user);
        Environment env = newEnvironment("Audit Test Environment 2");

        auditService.logLockReleased(saved.getUserId(), env.getEnvironmentId(), env.getDisplayName());

        awaitAsync(() -> {
            AuditLog logEntry = auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(l -> l.getAction() == AuditAction.LOCK_RELEASED)
                    .findFirst().orElseThrow();
            assertTrue(logEntry.getDetails().contains("jane.smith"), "details should contain the email-inferred username");
            assertFalse(logEntry.getDetails().contains(saved.getUserId()), "details should not contain the raw user id");
        });
    }

    @Test
    void aDeactivatedUserIsNamedNotGivenByTheirId() {
        // U23-T13: My activity read 'Deactivated user "1b626463-..."'.
        User saved = userRepository.save(User.fromAzureAd("audit-deact-oid", "deact.person@example.com", "Deact Person"));

        auditService.logUserDeactivated(null, saved.getUserId());

        awaitAsync(() -> {
            AuditLog logEntry = auditLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(l -> l.getAction() == AuditAction.USER_DEACTIVATED)
                    .findFirst().orElseThrow();
            assertEquals("Deact Person", logEntry.getTargetName());
            assertEquals(saved.getUserId(), logEntry.getTargetId());
        });
    }

    @Test
    void aVmRowOfARunNoPersonStartedSaysWhatRanIt() {
        // U24-T02: recorded under the person it runs as, with "Run by: ..." (before the error of a failure).
        User saved = userRepository.save(User.fromAzureAd("audit-runby-oid", "runs.as@example.com", "Runs As"));
        Environment env = newEnvironment("Run by");

        auditService.logVmStopped(saved.getUserId(), "vm-runby-1", "web-01", env.getEnvironmentId(), "Schedule \"ise start-stop\"");
        auditService.logVmStopFailed(saved.getUserId(), "vm-runby-2", "web-02", env.getEnvironmentId(), "throttled", "The lease");
        auditService.logVmStarted(saved.getUserId(), "vm-runby-3", "web-03", env.getEnvironmentId(), null);

        awaitAsync(() -> {
            Map<String, AuditLog> byTarget = new java.util.HashMap<>();
            auditLogRepository.findTop100ByOrderByCreatedAtDesc().forEach(l -> byTarget.putIfAbsent(l.getTargetId(), l));
            AuditLog stopped = byTarget.get("vm-runby-1");
            assertNotNull(stopped);
            assertEquals(saved.getUserId(), stopped.getUserId());
            assertTrue(stopped.getDetails().endsWith(". Run by: Schedule \"ise start-stop\""), stopped.getDetails());
            AuditLog failed = byTarget.get("vm-runby-2");
            assertNotNull(failed);
            assertTrue(failed.getDetails().endsWith(". Run by: The lease. Error: throttled"), failed.getDetails());
            AuditLog own = byTarget.get("vm-runby-3");
            assertNotNull(own);
            assertFalse(own.getDetails().contains("Run by:"), own.getDetails());
        });
    }

    @Test
    void testLogAction_CreatesAuditLog() {
        // Given
        String targetId = "env-001";
        String targetName = "Test Environment";

        // When - log asynchronously (userId is null to avoid FK constraint)
        auditService.logAction(null, AuditAction.ENVIRONMENT_CREATED,
                "environment", targetId, targetName, "Environment created");

        // Then
        awaitAsync(() -> {
            List<AuditLog> logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
            assertFalse(logs.isEmpty());

            AuditLog log = logs.get(0);
            assertEquals(AuditAction.ENVIRONMENT_CREATED, log.getAction());
            assertEquals("environment", log.getTargetType());
            assertEquals(targetId, log.getTargetId());
            assertEquals(targetName, log.getTargetName());
            assertTrue(log.getSuccess());
        });
    }

    @Test
    void testLogFailure_MarksAsUnsuccessful() {
        // Given
        String vmId = "vm-001";
        String errorMessage = "Connection timeout";

        // When
        auditService.logFailure(null, AuditAction.VM_START_FAILED,
                "vm", vmId, "web-server-1", errorMessage);

        // Then
        awaitAsync(() -> {
            List<AuditLog> logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
            assertFalse(logs.isEmpty());

            AuditLog log = logs.get(0);
            assertFalse(log.getSuccess());
            assertEquals(errorMessage, log.getErrorMessage());
        });
    }

    @Test
    void testLogActionSync_ImmediatelyPersisted() {
        // Given/When
        AuditLog created = auditService.logActionSync(AuditLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(null)
                .action(AuditAction.LOCK_ACQUIRED)
                .targetType("lock")
                .targetId("env-001")
                .targetName("Production")
                .details("Lock acquired for maintenance")
                .success(true));

        // Then - should be immediately available
        assertNotNull(created);
        assertNotNull(created.getLogId());

        AuditLog retrieved = auditLogRepository.findById(created.getLogId()).orElse(null);
        assertNotNull(retrieved);
    }

    @Test
    void testGetRecentLogs() {
        // Given - create some logs without FK references
        createSimpleLogs(5);

        // When
        List<AuditLog> logs = auditService.getRecentLogs();

        // Then
        assertEquals(5, logs.size());
    }

    @Test
    void testGetLogsInDateRange() {
        // Given
        createSimpleLogs(3);

        // When
        LocalDate today = LocalDate.now();
        Page<AuditLog> logs = auditService.getLogsInDateRange(
                today.minusDays(1), today.plusDays(1), 0, 10);

        // Then
        assertTrue(logs.getTotalElements() >= 3);
    }

    @Test
    void testGetFailedOperations() {
        // Given
        createSimpleFailedLogs(3);
        createSimpleLogs(2); // Successful logs

        // When
        Page<AuditLog> failedLogs = auditService.getFailedOperations(0, 10);

        // Then
        assertEquals(3, failedLogs.getTotalElements());
        assertTrue(failedLogs.getContent().stream().noneMatch(AuditLog::getSuccess));
    }

    @Test
    void testSearchLogs() {
        // Given
        AuditLog log = AuditLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(null)
                .action(AuditAction.VM_START_COMPLETED)
                .targetType("vm")
                .targetId("vm-001")
                .targetName("test-vm")
                .details("Special keyword: UNIQUE_MARKER")
                .success(true)
                .build();
        auditLogRepository.save(log);

        // When
        Page<AuditLog> results = auditService.searchLogs("UNIQUE_MARKER", 0, 10);

        // Then
        assertEquals(1, results.getTotalElements());
    }

    @Test
    void testGetActionCountsByEnvironment_MapsAggregateProjection() {
        // Given
        Environment environment = new Environment();
        environment.setEnvironmentId("env-report-001");
        environment.setName("report-env");
        environment.setDisplayName("Report Environment");
        environment.setIsActive(true);
        environmentRepository.save(environment);

        auditLogRepository.save(AuditLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(null)
                .action(AuditAction.VM_START_COMPLETED)
                .environmentId(environment.getEnvironmentId())
                .targetType("vm")
                .targetId("vm-report-001")
                .targetName("vm-report-001")
                .success(true)
                .build());
        auditLogRepository.save(AuditLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(null)
                .action(AuditAction.VM_STOP_COMPLETED)
                .environmentId(environment.getEnvironmentId())
                .targetType("vm")
                .targetId("vm-report-002")
                .targetName("vm-report-002")
                .success(true)
                .build());

        // When
        List<AuditService.EnvironmentActivitySummary> summaries =
                auditService.getActionCountsByEnvironment(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

        // Then
        AuditService.EnvironmentActivitySummary summary = summaries.stream()
                .filter(item -> environment.getEnvironmentId().equals(item.getEnvironmentId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Report Environment", summary.getEnvironmentName());
        assertEquals(2L, summary.getActionCount());
    }

    // ============= Helper Methods =============

    private void createSimpleLogs(int count) {
        for (int i = 0; i < count; i++) {
            AuditLog log = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(null) // Avoid FK constraint
                    .action(AuditAction.VM_REGISTERED)
                    .environmentId(null) // Avoid FK constraint
                    .targetType("vm")
                    .targetId("vm-" + i)
                    .targetName("vm-" + i)
                    .success(true)
                    .build();
            auditLogRepository.save(log);
        }
    }

    private void createSimpleFailedLogs(int count) {
        for (int i = 0; i < count; i++) {
            AuditLog log = AuditLog.builder()
                    .logId(UUID.randomUUID().toString())
                    .userId(null) // Avoid FK constraint
                    .action(AuditAction.VM_START_FAILED)
                    .targetType("vm")
                    .targetId("vm-" + i)
                    .targetName("vm-" + i)
                    .success(false)
                    .errorMessage("Error " + i)
                    .build();
            auditLogRepository.save(log);
        }
    }
}
