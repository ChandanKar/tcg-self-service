package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


class StateSyncServiceTest extends AbstractIntegrationTest {

    @Autowired
    private StateSyncService stateSyncService;

    @Autowired
    private VmTransitionalGuard transitionalGuard;

    @Autowired
    private VmStateHistoryRepository stateHistoryRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Environment testEnvironment;
    private VmGroup testGroup;
    private Vm testVm;
    private Object originalStaleTransitionalMinutes;

    @AfterEach
    void restoreReflectedSettings() {
        ReflectionTestUtils.setField(transitionalGuard, "staleTransitionalMinutes", originalStaleTransitionalMinutes);
    }

    @BeforeEach
    void setUp() {
        originalStaleTransitionalMinutes = ReflectionTestUtils.getField(transitionalGuard, "staleTransitionalMinutes");
        stateHistoryRepository.deleteAll();
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();

        // Create test data
        testEnvironment = new Environment();
        testEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        testEnvironment.setName("test-env");
        testEnvironment.setDisplayName("Test Environment");
        testEnvironment.setIsActive(true);
        testEnvironment = environmentRepository.save(testEnvironment);

        testGroup = new VmGroup();
        testGroup.setGroupId(UUID.randomUUID().toString());
        testGroup.setEnvironment(testEnvironment);
        testGroup.setName("test-group");
        testGroup.setDisplayName("Test Group");
        testGroup.setSequencePosition(1);
        testGroup = groupRepository.save(testGroup);

        testVm = new Vm();
        testVm.setVmId(UUID.randomUUID().toString());
        testVm.setGroup(testGroup);
        testVm.setName("test-vm-1");
        testVm.setDisplayName("Test VM 1");
        testVm.setProvider(CloudProvider.AWS);
        testVm.setRegion("ap-south-1");
        testVm.setProviderVmId("i-1234567890abcdef0");
        testVm.setSequencePosition(1);
        testVm.setStatus(VmStatus.RUNNING);
        testVm = vmRepository.save(testVm);

        // Setup mock cloud provider
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
    }

    @Test
    void testRecordStateChange_CreatesHistoryEntry() {
        // Given
        VmStatus previousStatus = VmStatus.STOPPED;
        VmStatus newStatus = VmStatus.RUNNING;

        // When - use null for changedByUserId to avoid FK constraint
        VmStateHistory history = stateSyncService.recordStateChange(
                testVm, previousStatus, newStatus, "user_action", null, null, "VM started");

        // Then
        assertNotNull(history);
        assertNotNull(history.getHistoryId());
        assertEquals(testVm.getVmId(), history.getVm().getVmId());
        assertEquals(previousStatus, history.getPreviousStatus());
        assertEquals(newStatus, history.getNewStatus());
        assertEquals("user_action", history.getChangeSource());
    }

    @Test
    void testGetVmStateHistory_ReturnsHistoryList() {
        // Given
        stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.STARTING, "user_action", null, null, "Starting");
        stateSyncService.recordStateChange(testVm, VmStatus.STARTING, VmStatus.RUNNING, "system", null, null, "Started");

        // When
        List<VmStateHistory> history = stateSyncService.getVmStateHistory(testVm.getVmId());

        // Then
        assertEquals(2, history.size());
        // Verify both statuses are present (order may vary due to timing)
        List<VmStatus> newStatuses = history.stream().map(VmStateHistory::getNewStatus).toList();
        assertTrue(newStatuses.contains(VmStatus.STARTING));
        assertTrue(newStatuses.contains(VmStatus.RUNNING));
    }

    @Test
    void testGetVmStateHistory_WithPagination() {
        // Given
        for (int i = 0; i < 5; i++) {
            stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "user_action", null, null, "Change " + i);
        }

        // When
        Page<VmStateHistory> page = stateSyncService.getVmStateHistory(testVm.getVmId(), 0, 3);

        // Then
        assertEquals(5, page.getTotalElements());
        assertEquals(3, page.getContent().size());
        assertEquals(2, page.getTotalPages());
    }

    @Test
    void testGetDriftEvents_ReturnsOnlyDriftEvents() {
        // Given
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift detected");
        stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "user_action", null, null, "User started");
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift again");

        // When
        Page<VmStateHistory> driftEvents = stateSyncService.getDriftEvents(0, 10);

        // Then
        assertEquals(2, driftEvents.getTotalElements());
        assertTrue(driftEvents.getContent().stream().allMatch(h -> "state_sync".equals(h.getChangeSource())));
    }

    @Test
    void testGetDriftEventsInRange() {
        // Given
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift");

        LocalDate today = LocalDate.now();

        // When
        List<VmStateHistory> events = stateSyncService.getDriftEventsInRange(today.minusDays(1), today.plusDays(1));

        // Then
        assertEquals(1, events.size());
    }

    @Test
    void testCountDriftEventsInRange() {
        // Given
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift 1");
        stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "state_sync", null, null, "Drift 2");

        LocalDate today = LocalDate.now();

        // When
        long count = stateSyncService.countDriftEventsInRange(today.minusDays(1), today.plusDays(1));

        // Then
        assertEquals(2, count);
    }

    @Test
    void testGetRecentStateChanges() {
        // Given
        for (int i = 0; i < 3; i++) {
            stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "user_action", null, null, "Change");
        }

        // When
        List<VmStateHistory> recent = stateSyncService.getRecentStateChanges();

        // Then
        assertTrue(recent.size() >= 3);
    }

    @Test
    void testGetSyncStatus_InitialState() {
        // The service is a shared singleton: another test class may already have run a sync.
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(stateSyncService);
        ((java.util.concurrent.atomic.AtomicReference<String>) ReflectionTestUtils.getField(target, "lastSyncStatus")).set("never");
        ((java.util.concurrent.atomic.AtomicReference<?>) ReflectionTestUtils.getField(target, "lastSyncTime")).set(null);

        // When
        StateSyncStatusDTO status = stateSyncService.getSyncStatus();

        // Then
        assertNotNull(status);
        assertEquals("never", status.getLastSyncStatus());
        assertFalse(status.isSyncInProgress());
    }

    @Test
    void testSyncVmState_NoDrift() {
        // Given - cloud status matches local status
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        // When
        boolean hasDrift = stateSyncService.syncVmState(testVm);

        // Then
        assertFalse(hasDrift);

        // Verify lastStateSyncAt was updated
        Vm updatedVm = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertNotNull(updatedVm.getLastStateSyncAt());
    }

    @Test
    void testSyncVmState_StripsMarkupFromCloudNameTag() {
        // Given - VM still named after its instance id, and the EC2 Name tag contains markup
        testVm.setName(testVm.getProviderVmId());
        testVm.setDisplayName(testVm.getProviderVmId());
        testVm = vmRepository.save(testVm);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);
        when(awsCloudProviderService.getVmName(anyString(), anyString())).thenReturn("<b>web-01</b>");

        // When
        stateSyncService.syncVmState(testVm);

        // Then
        Vm updatedVm = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals("bweb-01/b", updatedVm.getDisplayName());
        assertFalse(updatedVm.getName().contains("<"), "stored name has no angle brackets");
    }

    @Test
    void testSyncVmState_WithDrift() {
        // Given - cloud status differs from local status
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);

        // When
        boolean hasDrift = stateSyncService.syncVmState(testVm);

        // Then
        assertTrue(hasDrift);

        // Verify VM status was updated
        Vm updatedVm = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(VmStatus.STOPPED, updatedVm.getStatus());

        // Verify state history was recorded
        List<VmStateHistory> history = stateSyncService.getVmStateHistory(testVm.getVmId());
        assertFalse(history.isEmpty());
        assertEquals("state_sync", history.get(0).getChangeSource());
    }

    @Test
    void testSyncAllVmStates() {
        // Given
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        // When
        StateSyncStatusDTO result = stateSyncService.syncAllVmStates();

        // Then
        assertNotNull(result);
        assertEquals("success", result.getLastSyncStatus());
        assertTrue(result.getTotalVmsSynced() >= 1);
    }

    @Test
    void testSyncEnvironmentVmStates() {
        // Given
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED); // Drift

        // When
        int driftCount = stateSyncService.syncEnvironmentVmStates(testEnvironment.getEnvironmentId());

        // Then
        assertEquals(1, driftCount);
    }

    @Test
    void testSyncInProgress_PreventsParallelSync() {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        StateSyncStatusDTO result = stateSyncService.syncAllVmStates();

        assertFalse(stateSyncService.isSyncInProgress());
    }

    @Test
    void testSyncVmState_SkipsSync_WhenVmIsStarting() {
        testVm.setStatus(VmStatus.STARTING);
        testVm = vmRepository.save(testVm);

        boolean hasDrift = stateSyncService.syncVmState(testVm);

        assertFalse(hasDrift);
        // Cloud provider must not be called — VM is mid-operation
        verify(awsCloudProviderService, never()).getVmStatus(anyString(), anyString());
        // Status must remain STARTING (not overwritten by sync)
        Vm unchanged = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(VmStatus.STARTING, unchanged.getStatus());
    }

    @Test
    void testSyncVmState_SkipsSync_WhenVmIsStopping() {
        testVm.setStatus(VmStatus.STOPPING);
        testVm = vmRepository.save(testVm);

        boolean hasDrift = stateSyncService.syncVmState(testVm);

        assertFalse(hasDrift);
        verify(awsCloudProviderService, never()).getVmStatus(anyString(), anyString());
        Vm unchanged = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(VmStatus.STOPPING, unchanged.getStatus());
    }

    @Test
    void testSyncVmState_ReconcilesStaleTransitionalVm_WhenStuckStarting() {
        // A VM whose status was orphaned mid-operation (e.g. a crashed operation that never
        // wrote back a terminal status) must eventually be corrected, not skipped forever.
        ReflectionTestUtils.setField(transitionalGuard, "staleTransitionalMinutes", 0L);
        testVm.setStatus(VmStatus.STARTING);
        testVm = vmRepository.save(testVm);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        boolean hasDrift = stateSyncService.syncVmState(testVm);

        assertTrue(hasDrift);
        verify(awsCloudProviderService).getVmStatus(anyString(), anyString());
        Vm reconciled = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(VmStatus.RUNNING, reconciled.getStatus());
    }

    @Test
    void testSyncVmState_ReconcilesStaleTransitionalVm_WhenStuckStopping() {
        ReflectionTestUtils.setField(transitionalGuard, "staleTransitionalMinutes", 0L);
        testVm.setStatus(VmStatus.STOPPING);
        testVm = vmRepository.save(testVm);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);

        boolean hasDrift = stateSyncService.syncVmState(testVm);

        assertTrue(hasDrift);
        Vm reconciled = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(VmStatus.STOPPED, reconciled.getStatus());
    }

    @Test
    void testSyncVmState_StillSkipsFreshTransitionalVm_RegardlessOfThreshold() {
        // Sanity check: a VM that just entered STARTING (updatedAt = now) must still be
        // skipped under the real default threshold, not just under the test's threshold=0 cases.
        testVm.setStatus(VmStatus.STARTING);
        testVm = vmRepository.save(testVm);

        boolean hasDrift = stateSyncService.syncVmState(testVm);

        assertFalse(hasDrift);
        verify(awsCloudProviderService, never()).getVmStatus(anyString(), anyString());
    }

    // ---- E09-T06 (M7): several consecutive NOT_FOUND results before a VM is deactivated ----

    private int auditRows(String action) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = ? AND target_id = ?",
                Integer.class, action, testVm.getVmId());
        return n == null ? 0 : n;
    }

    @Test
    void notFoundBelowTheThresholdFlagsDriftButKeepsTheVm() {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.NOT_FOUND);

        assertFalse(stateSyncService.syncVmState(testVm));
        assertFalse(stateSyncService.syncVmState(testVm));

        Vm after = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertTrue(after.getIsActive());
        assertEquals(VmStatus.RUNNING, after.getStatus());
        assertTrue(after.getStateDriftDetected());
        assertEquals(2, after.getNotFoundCount());
        assertTrue(stateSyncService.getVmStateHistory(testVm.getVmId()).isEmpty());
    }

    @Test
    void theThirdConsecutiveNotFoundDeactivates() {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.NOT_FOUND);

        stateSyncService.syncVmState(testVm);
        stateSyncService.syncVmState(testVm);
        assertTrue(stateSyncService.syncVmState(testVm));

        Vm after = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertFalse(after.getIsActive());
        assertEquals(VmStatus.NOT_FOUND, after.getStatus());
        awaitAsync(() -> { // audit rows are written on the async executor
            assertEquals(1, auditRows("VM_DEACTIVATED"));
            assertEquals(1, auditRows("STATE_DRIFT_DETECTED"));
        });
    }

    @Test
    void terminatedDeactivatesAtOnce() {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.TERMINATED);

        assertTrue(stateSyncService.syncVmState(testVm));

        Vm after = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertFalse(after.getIsActive());
        assertEquals(VmStatus.TERMINATED, after.getStatus());
        awaitAsync(() -> assertEquals(1, auditRows("VM_DEACTIVATED")));
    }

    @Test
    void findingTheInstanceAgainEndsTheStreak() {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.NOT_FOUND);
        stateSyncService.syncVmState(testVm);
        stateSyncService.syncVmState(testVm);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        stateSyncService.syncVmState(testVm);

        Vm after = vmRepository.findById(testVm.getVmId()).orElseThrow();
        assertEquals(0, after.getNotFoundCount());
        assertFalse(after.getStateDriftDetected());
        assertTrue(after.getIsActive());
    }
}
