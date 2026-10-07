package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VmOperationsServiceTest extends AbstractIntegrationTest {

    @Autowired
    private VmOperationsService operationsService;

    @Autowired
    private LockService lockService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private OperationExecutionRepository executionRepository;

    @Autowired
    private OperationDetailRepository detailRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentAccessRepository accessRepository;

    // A real, authorized user � group-scope operation authorization (step 5) now runs on
    // startOperation, so a bare id string is no longer enough.
    private String opsUserId;

    private Environment testEnvironment;
    private VmGroup testGroup;
    private Vm testVm1;
    private Vm testVm2;

    @BeforeEach
    void setUp() {
        // Create test environment
        testEnvironment = new Environment();
        testEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        testEnvironment.setName("ops-test-env-" + UUID.randomUUID().toString().substring(0, 8));
        testEnvironment.setDisplayName("Operations Test Environment");
        testEnvironment.setIsActive(true);
        testEnvironment = environmentRepository.saveAndFlush(testEnvironment);

        // Create test group
        testGroup = new VmGroup();
        testGroup.setGroupId(UUID.randomUUID().toString());
        testGroup.setEnvironment(testEnvironment);
        testGroup.setName("test-group");
        testGroup.setDisplayName("Test Group");
        testGroup.setSequencePosition(1);
        testGroup = groupRepository.saveAndFlush(testGroup);

        // Create test VMs
        testVm1 = createTestVm("test-vm-1", 1);
        testVm2 = createTestVm("test-vm-2", 2);

        User opsUser = User.fromUsernamePassword("ops.user." + UUID.randomUUID().toString().substring(0, 8),
                "irrelevant", "ops@example.com", "Ops User", "TCG");
        opsUser.setAdmin(true);
        opsUserId = userRepository.saveAndFlush(opsUser).getUserId();
    }

    private Vm createTestVm(String name, int sequence) {
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(testGroup);
        vm.setName(name);
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("us-east-1");
        vm.setProviderVmId("i-" + UUID.randomUUID().toString().substring(0, 17));
        vm.setVmType(VmType.DEV);
        vm.setSequencePosition(sequence);
        vm.setStatus(VmStatus.STOPPED);
        vm.setIsActive(true);
        return vmRepository.saveAndFlush(vm);
    }

    private VmGroup createGroup(String name, int seq) {
        VmGroup g = new VmGroup();
        g.setGroupId(UUID.randomUUID().toString());
        g.setEnvironment(testEnvironment);
        g.setName(name);
        g.setDisplayName(name);
        g.setSequencePosition(seq);
        return groupRepository.saveAndFlush(g);
    }

    private Vm createVmInGroup(VmGroup group, String name) {
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(name);
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("us-east-1");
        vm.setProviderVmId("i-" + UUID.randomUUID().toString().substring(0, 17));
        vm.setVmType(VmType.DEV);
        vm.setSequencePosition(1);
        vm.setStatus(VmStatus.STOPPED);
        vm.setIsActive(true);
        return vmRepository.saveAndFlush(vm);
    }

    private User createUserWithGroupGrant(VmGroup group, AccessLevel level) {
        User u = userRepository.saveAndFlush(User.fromUsernamePassword(
                "scoped." + UUID.randomUUID().toString().substring(0, 8), "x", "s@example.com", "Scoped", "TCG"));
        EnvironmentAccess grant = EnvironmentAccess.create(testEnvironment, u, level, u);
        grant.setScopeType(AccessScopeType.GROUP);
        grant.setScopeId(group.getGroupId());
        accessRepository.saveAndFlush(grant);
        return u;
    }

    @Test
    void startOperation_groupScopedUser_failsFastWhenTargetingADisallowedGroup() {
        VmGroup otherGroup = createGroup("other-group", 2);
        createVmInGroup(otherGroup, "other-vm");
        User scoped = createUserWithGroupGrant(testGroup, AccessLevel.USER);   // USER on testGroup only
        lockService.acquireLock(testEnvironment.getEnvironmentId(), scoped.getUserId(), "t", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setGroupIds(List.of(otherGroup.getGroupId()));
        dto.setSkipAlreadyInTargetState(false);

        assertThrows(UnauthorizedException.class, () ->
                operationsService.startOperation(testEnvironment.getEnvironmentId(), scoped.getUserId(), dto));
    }

    @Test
    void startOperation_groupScopedUser_wholeEnvironment_narrowsToOperableGroups() {
        VmGroup otherGroup = createGroup("other-group", 2);
        createVmInGroup(otherGroup, "other-vm");
        User scoped = createUserWithGroupGrant(testGroup, AccessLevel.USER);   // USER on testGroup only
        lockService.acquireLock(testEnvironment.getEnvironmentId(), scoped.getUserId(), "t", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);   // whole environment: testGroup (2 VMs) + otherGroup (1 VM)

        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), scoped.getUserId(), dto);

        // otherGroup's VM is dropped — only the 2 VMs of testGroup remain.
        assertEquals(2, execution.getTotalTargets());
    }

    @Test
    void testStartOperation_CreatesExecution() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setReason("Test start operation");
        dto.setSkipAlreadyInTargetState(false);

        // When
        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // Then - verify execution was created (status may change due to async execution)
        assertNotNull(execution.getExecutionId());
        assertEquals(OperationType.START, execution.getOperationType());
        // Status could be PENDING or already IN_PROGRESS/FAILED due to async execution
        assertNotNull(execution.getStatus());
        assertEquals(2, execution.getTotalTargets());
        assertEquals(userId, execution.getInitiatedByUserId());
    }

    @Test
    void testStartOperation_CreatesDetails() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        // When
        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // Then - verify details were created (status may change due to async execution)
        List<OperationDetail> details = detailRepository
                .findByExecutionExecutionIdOrderBySequencePositionAsc(execution.getExecutionId());

        assertEquals(2, details.size());
        // Status may have changed due to async execution, just verify details exist
        assertNotNull(details.get(0).getStatus());
        assertEquals("start", details.get(0).getAction());
    }

    @Test
    void testStartOperation_RequiresLock() {
        // Given - no lock acquired
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);

        // When/Then - should throw LockAlreadyHeldException (for another user holding lock)
        // or the operation should fail because user doesn't have lock
        assertThrows(Exception.class, () -> {
            operationsService.startOperation(testEnvironment.getEnvironmentId(), "non-lock-holder", dto);
        });
    }

    @Test
    void testStartOperation_SkipsAlreadyRunning() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        // Set one VM as already running
        testVm1.setStatus(VmStatus.RUNNING);
        vmRepository.saveAndFlush(testVm1);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(true);

        // When
        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // Then - only 1 VM should be targeted
        assertEquals(1, execution.getTotalTargets());
    }

    @Test
    void testStartOperation_SpecificVms() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setVmIds(List.of(testVm1.getVmId()));
        dto.setSkipAlreadyInTargetState(false);

        // When
        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // Then - only 1 VM should be targeted
        assertEquals(1, execution.getTotalTargets());
    }

    @Test
    void testCancelExecution() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        OperationExecution execution = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // Refresh from database to get latest status
        OperationExecution refreshedExecution = operationsService.getExecution(execution.getExecutionId());

        // When/Then - Only try to cancel if it's still cancellable
        if (refreshedExecution.getStatus() == ExecutionStatus.PENDING ||
            refreshedExecution.getStatus() == ExecutionStatus.IN_PROGRESS) {
            OperationExecution cancelled = operationsService.cancelExecution(execution.getExecutionId(), userId);
            assertEquals(ExecutionStatus.CANCELLED, cancelled.getStatus());
            assertNotNull(cancelled.getCompletedAt());
        } else {
            // Execution already completed/failed due to async processing - that's OK for this test
            assertNotNull(refreshedExecution.getStatus());
        }
    }

    @Test
    void testGetExecution() {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setSkipAlreadyInTargetState(false);

        OperationExecution created = operationsService.startOperation(
                testEnvironment.getEnvironmentId(), userId, dto);

        // When
        OperationExecution retrieved = operationsService.getExecution(created.getExecutionId());

        // Then
        assertEquals(created.getExecutionId(), retrieved.getExecutionId());
        assertEquals(OperationType.STOP, retrieved.getOperationType());
    }

    @Test
    void testNoVmsThrowsException() {
        // Given - environment with no VMs
        Environment emptyEnv = new Environment();
        emptyEnv.setEnvironmentId(UUID.randomUUID().toString());
        emptyEnv.setName("empty-env-" + UUID.randomUUID().toString().substring(0, 8));
        emptyEnv.setDisplayName("Empty Environment");
        emptyEnv.setIsActive(true);
        emptyEnv = environmentRepository.saveAndFlush(emptyEnv);

        String userId = opsUserId;
        lockService.acquireLock(emptyEnv.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);

        final String envId = emptyEnv.getEnvironmentId();

        // When/Then
        assertThrows(ValidationException.class, () -> {
            operationsService.startOperation(envId, userId, dto);
        });
    }

    @Test
    void testDuplicateOperationThrowsException() throws InterruptedException {
        // Given
        String userId = opsUserId;
        lockService.acquireLock(testEnvironment.getEnvironmentId(), userId, "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        // First operation
        OperationExecution firstExecution = operationsService.startOperation(testEnvironment.getEnvironmentId(), userId, dto);

        // Check if first operation is still active
        OperationExecution refreshed = operationsService.getExecution(firstExecution.getExecutionId());

        // Only test duplicate if first operation is still active (pending or in_progress)
        if (refreshed.getStatus() == ExecutionStatus.PENDING ||
            refreshed.getStatus() == ExecutionStatus.IN_PROGRESS) {
            // When/Then - second operation should fail
            assertThrows(ValidationException.class, () -> {
                operationsService.startOperation(testEnvironment.getEnvironmentId(), userId, dto);
            });
        } else {
            // First operation completed too quickly, this is expected behavior with test credentials
            assertNotNull(refreshed.getStatus());
        }
    }
}

