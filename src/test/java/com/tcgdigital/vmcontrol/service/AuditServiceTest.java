package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @BeforeEach
    void setUp() {
        // Clear audit logs for clean test state
        auditLogRepository.deleteAll();
    }

    @Test
    void testLogAction_CreatesAuditLog() throws InterruptedException {
        // Given
        String targetId = "env-001";
        String targetName = "Test Environment";

        // When - log asynchronously (userId is null to avoid FK constraint)
        auditService.logAction(null, AuditAction.ENVIRONMENT_CREATED,
                "environment", targetId, targetName, "Environment created");

        // Wait for async execution
        Thread.sleep(500);

        // Then
        List<AuditLog> logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
        assertFalse(logs.isEmpty());

        AuditLog log = logs.get(0);
        assertEquals(AuditAction.ENVIRONMENT_CREATED, log.getAction());
        assertEquals("environment", log.getTargetType());
        assertEquals(targetId, log.getTargetId());
        assertEquals(targetName, log.getTargetName());
        assertTrue(log.getSuccess());
    }

    @Test
    void testLogFailure_MarksAsUnsuccessful() throws InterruptedException {
        // Given
        String vmId = "vm-001";
        String errorMessage = "Connection timeout";

        // When
        auditService.logFailure(null, AuditAction.VM_START_FAILED,
                "vm", vmId, "web-server-1", errorMessage);

        Thread.sleep(500);

        // Then
        List<AuditLog> logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
        assertFalse(logs.isEmpty());

        AuditLog log = logs.get(0);
        assertFalse(log.getSuccess());
        assertEquals(errorMessage, log.getErrorMessage());
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
