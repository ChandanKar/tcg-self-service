package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Keeping platform-stopped RDS databases stopped through AWS's seven-day automatic start (E33-T04). */
@ExtendWith(MockitoExtension.class)
class RdsKeepStoppedServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    @Mock private VmRepository vmRepository;
    @Mock private RdsCloudProviderService rds;
    @Mock private OperationExecutionRepository executions;
    @Mock private StateSyncService stateSync;
    @Mock private AuditService audit;
    @Mock private NotificationService notifications;
    @Mock private AutomationRuleService automation;

    private RdsKeepStoppedService service;
    private Vm vm;

    @BeforeEach
    void setUp() {
        service = new RdsKeepStoppedService(vmRepository, rds, executions, stateSync, audit);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "keepStopped", true);
        ReflectionTestUtils.setField(service, "notificationService", notifications);
        ReflectionTestUtils.setField(service, "automationRuleService", automation);
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("qa");
        VmGroup group = new VmGroup();
        group.setGroupId("g-1");
        group.setEnvironment(env);
        vm = new Vm();
        vm.setVmId("vm-db");
        vm.setName("orders-db");
        vm.setGroup(group);
        vm.setProvider(CloudProvider.AWS_RDS);
        vm.setProviderVmId("db:orders");
        vm.setRegion("ap-south-1");
        vm.setStatus(VmStatus.STOPPED);
        vm.setIntendedStoppedSince(Timestamp.from(NOW.minus(Duration.ofDays(7)).minusSeconds(600)));
        lenient().when(rds.isAvailable()).thenReturn(true);
        lenient().when(vmRepository.findRdsIntendedStopped()).thenReturn(List.of(vm));
        lenient().when(executions.findActiveWithEnvironment(null)).thenReturn(List.of());
        lenient().when(vmRepository.markAutoStartDetected(eq("vm-db"), any())).thenReturn(1);
        lenient().when(rds.requestStop(anyString(), any())).thenReturn(CloudProviderService.VmOperationResult.success("db:orders", VmStatus.STOPPING));
    }

    private void awsReports(VmStatus status) {
        when(rds.getVmStatusBatch(anyList(), any())).thenReturn(Map.of("db:orders", status));
    }

    @Test
    void anAutoStartedDatabaseIsStoppedAgainOnceAvailableAndBothAreAudited() {
        awsReports(VmStatus.RUNNING);

        assertEquals(1, service.run());

        verify(rds).requestStop(eq("db:orders"), any());
        verify(vmRepository).markRestopped(eq("vm-db"), eq(Timestamp.from(NOW)));
        verify(stateSync).recordStateChange(vm, VmStatus.STOPPED, VmStatus.RUNNING, RdsKeepStoppedService.SOURCE_AUTO_START,
                null, null, "AWS started the stopped database after seven days");
        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.RDS_AUTO_START_DETECTED), eq("env-1"), eq("qa"),
                eq("VM"), eq("vm-db"), eq("orders-db"), anyString());
        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.RDS_RESTOPPED), eq("env-1"), eq("qa"),
                eq("VM"), eq("vm-db"), eq("orders-db"), anyString());
        verify(notifications).notifyRdsAutoStart("env-1", "qa", "vm-db", "orders-db", true);
    }

    @Test
    void whileStartingItIsRecordedButNotYetStopped() {
        awsReports(VmStatus.STARTING);

        assertEquals(0, service.run());

        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.RDS_AUTO_START_DETECTED), any(), any(), any(), any(), any(), any());
        verify(rds, never()).requestStop(anyString(), any());
    }

    @Test
    void anAlreadyRecordedStartIsNotAuditedAgain() {
        vm.setAutoStartDetectedAt(Timestamp.from(NOW.minusSeconds(900)));
        awsReports(VmStatus.RUNNING);

        assertEquals(1, service.run());

        verify(vmRepository, never()).markAutoStartDetected(anyString(), any());
        verify(audit, never()).logEnvironmentAction(any(), eq(AuditAction.RDS_AUTO_START_DETECTED), any(), any(), any(), any(), any(), any());
        verify(rds).requestStop(eq("db:orders"), any());
    }

    @Test
    void aDatabaseStartedOutsideThePlatformBeforeTheSevenDaysIsLeftRunning() {
        vm.setIntendedStoppedSince(Timestamp.from(NOW.minus(Duration.ofDays(2))));
        awsReports(VmStatus.RUNNING);

        assertEquals(0, service.run());

        verify(vmRepository).clearIntendedStopped("vm-db");
        verify(rds, never()).requestStop(anyString(), any());
        verifyNoInteractions(audit, notifications);
    }

    @Test
    void withKeepStoppedOffItIsOnlyRecordedAndOwnersAreWarned() {
        ReflectionTestUtils.setField(service, "keepStopped", false);
        awsReports(VmStatus.RUNNING);

        assertEquals(0, service.run());

        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.RDS_AUTO_START_DETECTED), any(), any(), any(), any(), any(), any());
        verify(notifications).notifyRdsAutoStart("env-1", "qa", "vm-db", "orders-db", false);
        verify(rds, never()).requestStop(anyString(), any());
    }

    @Test
    void aRunningPlatformOperationOrAnImminentScheduledStartLeavesItAlone() {
        OperationExecution running = new OperationExecution();
        running.setEnvironment(vm.getGroup().getEnvironment());
        when(executions.findActiveWithEnvironment(null)).thenReturn(List.of(running));
        awsReports(VmStatus.RUNNING);

        assertEquals(0, service.run());
        verifyNoInteractions(audit);

        when(executions.findActiveWithEnvironment(null)).thenReturn(List.of());
        when(automation.startsWithin(eq("env-1"), any())).thenReturn(true);

        assertEquals(0, service.run());
        verify(rds, never()).requestStop(anyString(), any());
    }

    @Test
    void stoppedDatabasesAndAnRdsSwitchedOffAreNotTouched() {
        awsReports(VmStatus.STOPPED);
        assertEquals(0, service.run());
        verifyNoInteractions(audit);

        when(rds.isAvailable()).thenReturn(false);
        clearInvocations(vmRepository);
        assertEquals(0, service.run());
        verifyNoInteractions(vmRepository);
    }
}
