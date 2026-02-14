package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException;
import com.tcgdigital.vmcontrol.exception.NoActiveLockException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.LockHistory;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.LockHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LockServiceTest {

    @Autowired
    private LockService lockService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private EnvironmentLockRepository lockRepository;

    @Autowired
    private LockHistoryRepository historyRepository;

    private Environment testEnvironment;

    @BeforeEach
    void setUp() {
        // Create test environment
        testEnvironment = new Environment();
        testEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        testEnvironment.setName("test-env-" + UUID.randomUUID().toString().substring(0, 8));
        testEnvironment.setDisplayName("Test Environment");
        testEnvironment.setIsActive(true);
        testEnvironment = environmentRepository.saveAndFlush(testEnvironment);
    }

    @Test
    void testAcquireLock_Success() {
        // Given
        String userId = "user-001";
        String reason = "Deploying new version";

        // When
        EnvironmentLock lock = lockService.acquireLock(
                testEnvironment.getEnvironmentId(), userId, reason, 30);

        // Then
        assertNotNull(lock.getLockId());
        assertEquals(userId, lock.getLockedByUserId());
        assertEquals(reason, lock.getLockReason());
        assertEquals(30, lock.getExpectedDurationMinutes());
        assertTrue(lock.getIsActive());
    }

    @Test
    void testAcquireLock_SameUserCanReacquire() {
        // Given
        String userId = "user-001";
        EnvironmentLock firstLock = lockService.acquireLock(
                testEnvironment.getEnvironmentId(), userId, "First lock", null);

        // When - same user tries to acquire again
        EnvironmentLock secondLock = lockService.acquireLock(
                testEnvironment.getEnvironmentId(), userId, "Second attempt", null);

        // Then - should return the existing lock
        assertEquals(firstLock.getLockId(), secondLock.getLockId());
    }

    @Test
    void testAcquireLock_DifferentUserBlocked() {
        // Given
        String user1 = "user-001";
        String user2 = "user-002";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), user1, "User 1 lock", null);

        // When/Then
        LockAlreadyHeldException exception = assertThrows(LockAlreadyHeldException.class, () -> {
            lockService.acquireLock(testEnvironment.getEnvironmentId(), user2, "User 2 attempt", null);
        });

        assertTrue(exception.getMessage().contains(user1));
    }

    @Test
    void testReleaseLock_Success() {
        // Given
        String userId = "user-001";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test lock", null);

        // When
        lockService.releaseLock(testEnvironment.getEnvironmentId(), userId);

        // Then
        assertFalse(lockService.isEnvironmentLocked(testEnvironment.getEnvironmentId()));
    }

    @Test
    void testReleaseLock_DifferentUserFails() {
        // Given
        String user1 = "user-001";
        String user2 = "user-002";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), user1, "User 1 lock", null);

        // When/Then
        assertThrows(UnauthorizedException.class, () -> {
            lockService.releaseLock(testEnvironment.getEnvironmentId(), user2);
        });
    }

    @Test
    void testReleaseLock_NoLockExists() {
        // When/Then
        assertThrows(NoActiveLockException.class, () -> {
            lockService.releaseLock(testEnvironment.getEnvironmentId(), "any-user");
        });
    }

    @Test
    void testBreakLock_Success() {
        // Given
        String userId = "user-001";
        String adminId = "admin-001";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "User lock", null);

        // When
        lockService.breakLock(testEnvironment.getEnvironmentId(), adminId, "Emergency maintenance");

        // Then
        assertFalse(lockService.isEnvironmentLocked(testEnvironment.getEnvironmentId()));

        // Verify lock was marked as broken
        List<EnvironmentLock> locks = lockRepository.findByEnvironmentEnvironmentIdOrderByLockedAtDesc(
                testEnvironment.getEnvironmentId());
        EnvironmentLock brokenLock = locks.get(0);
        assertTrue(brokenLock.wasBroken());
        assertEquals(adminId, brokenLock.getBrokenByAdminUserId());
    }

    @Test
    void testBreakLock_NoLockExists() {
        // When/Then
        assertThrows(NoActiveLockException.class, () -> {
            lockService.breakLock(testEnvironment.getEnvironmentId(), "admin-001", "Reason");
        });
    }

    @Test
    void testIsEnvironmentLocked() {
        // Initially not locked
        assertFalse(lockService.isEnvironmentLocked(testEnvironment.getEnvironmentId()));

        // Lock it
        lockService.acquireLock(testEnvironment.getEnvironmentId(), "user-001", "Test", null);
        assertTrue(lockService.isEnvironmentLocked(testEnvironment.getEnvironmentId()));

        // Release it
        lockService.releaseLock(testEnvironment.getEnvironmentId(), "user-001");
        assertFalse(lockService.isEnvironmentLocked(testEnvironment.getEnvironmentId()));
    }

    @Test
    void testGetCurrentLock() {
        // No lock
        Optional<EnvironmentLock> noLock = lockService.getCurrentLock(testEnvironment.getEnvironmentId());
        assertTrue(noLock.isEmpty());

        // With lock
        lockService.acquireLock(testEnvironment.getEnvironmentId(), "user-001", "Test", null);
        Optional<EnvironmentLock> withLock = lockService.getCurrentLock(testEnvironment.getEnvironmentId());
        assertTrue(withLock.isPresent());
        assertEquals("user-001", withLock.get().getLockedByUserId());
    }

    @Test
    void testVerifyLockPermission_NoLockAllowsAll() {
        // When/Then - should not throw
        assertDoesNotThrow(() -> {
            lockService.verifyLockPermission(testEnvironment.getEnvironmentId(), "any-user");
        });
    }

    @Test
    void testVerifyLockPermission_LockHolderAllowed() {
        // Given
        String userId = "user-001";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        // When/Then - lock holder should be allowed
        assertDoesNotThrow(() -> {
            lockService.verifyLockPermission(testEnvironment.getEnvironmentId(), userId);
        });
    }

    @Test
    void testVerifyLockPermission_OtherUserBlocked() {
        // Given
        lockService.acquireLock(testEnvironment.getEnvironmentId(), "user-001", "Test", null);

        // When/Then
        assertThrows(LockAlreadyHeldException.class, () -> {
            lockService.verifyLockPermission(testEnvironment.getEnvironmentId(), "user-002");
        });
    }

    @Test
    void testLockHistory_RecordsActions() {
        // Given
        String userId = "user-001";

        // When
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test lock", null);
        lockService.releaseLock(testEnvironment.getEnvironmentId(), userId);

        // Then
        List<LockHistory> history = lockService.getLockHistory(testEnvironment.getEnvironmentId());
        assertEquals(2, history.size());
        // Most recent first
        assertEquals("RELEASED", history.get(0).getAction().name());
        assertEquals("ACQUIRED", history.get(1).getAction().name());
    }

    @Test
    void testGetLocksHeldByUser() {
        // Create another environment
        Environment env2 = new Environment();
        env2.setEnvironmentId(UUID.randomUUID().toString());
        env2.setName("test-env-2-" + UUID.randomUUID().toString().substring(0, 8));
        env2.setDisplayName("Test Environment 2");
        env2.setIsActive(true);
        env2 = environmentRepository.saveAndFlush(env2);

        // User holds locks on both
        String userId = "user-001";
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Lock 1", null);
        lockService.acquireLock(env2.getEnvironmentId(), userId, "Lock 2", null);

        // When
        List<EnvironmentLock> userLocks = lockService.getLocksHeldByUser(userId);

        // Then
        assertEquals(2, userLocks.size());
    }
}
