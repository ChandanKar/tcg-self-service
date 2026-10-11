package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.IdleStopSnooze;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.LeaseService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.VmOperationsService;
import com.tcgdigital.vmcontrol.service.idle.IdleStopSnoozeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Extend-lease and snooze-idle-stop chat actions (E24-T05): success, the lease's limits, stale links. */
class ChatActionServiceTest {

    private static final String ENV = "env-1";

    private ChatActionTokenService tokens;
    private LeaseService leaseService;
    private EnvironmentLeaseRepository leases;
    private IdleStopSnoozeService snoozes;
    private VmRepository vms;
    private AuditService audit;
    private ChatActionService service;

    @BeforeEach
    void setUp() {
        tokens = mock(ChatActionTokenService.class);
        leaseService = mock(LeaseService.class);
        leases = mock(EnvironmentLeaseRepository.class);
        snoozes = mock(IdleStopSnoozeService.class);
        vms = mock(VmRepository.class);
        audit = mock(AuditService.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        Environment env = new Environment(ENV);
        env.setName("dev-qa");
        env.setDisplayName("Dev QA");
        when(environments.findById(ENV)).thenReturn(Optional.of(env));
        service = new ChatActionService(tokens, mock(EnvironmentAccessService.class), mock(VmOperationsService.class),
                mock(SecurityService.class), environments, audit, leaseService, leases, snoozes, vms);
    }

    private void link(ChatActionType type) {
        when(tokens.verify("t")).thenReturn(new ChatActionTokenService.Claims("jti-1", type, ENV, null, Instant.now().plusSeconds(600)));
    }

    private void leaseEndingAt(Instant end) {
        EnvironmentLease lease = mock(EnvironmentLease.class);
        when(lease.getEndsAt()).thenReturn(Timestamp.from(end));
        when(leases.findActiveByEnvironmentId(ENV)).thenReturn(Optional.of(lease));
    }

    private void vmsIn(VmStatus... statuses) {
        List<Vm> list = java.util.Arrays.stream(statuses).map(s -> {
            Vm vm = new Vm();
            vm.setStatus(s);
            return vm;
        }).toList();
        when(vms.findByEnvironmentId(ENV)).thenReturn(list);
    }

    @Test
    void extendMovesTheLeaseEnd() {
        link(ChatActionType.EXTEND_LEASE);
        leaseEndingAt(Instant.parse("2026-10-13T10:15:00Z"));
        LeaseDTO extended = new LeaseDTO();
        extended.setEndsAt(Timestamp.from(Instant.parse("2026-10-13T12:15:00Z")));
        when(leaseService.extend(ENV, 120, "Extended via chat")).thenReturn(new LeaseService.ExtendOutcome(extended, null));

        ChatActionService.Preview preview = service.preview("t");
        assertThat(preview.choices()).containsExactly(60, 120, 240);
        assertThat(preview.defaultChoice()).isEqualTo(120);
        assertThat(preview.description()).contains("It now stops at 10:15 UTC");

        ChatActionService.Result result = service.execute("t", "user-1", null);
        assertThat(result.message()).isEqualTo("Dev QA now runs until 12:15 UTC.");
        verify(tokens).markUsed("jti-1", "user-1");
        verify(audit).logAction(eq("user-1"), any(), eq("chat_action"), eq("jti-1"), anyString(), contains("via chat"));
    }

    @Test
    void beyondTheMaximumTheOwnersAreAskedOrTheLimitErrorShows() {
        link(ChatActionType.EXTEND_LEASE);
        leaseEndingAt(Instant.now().plusSeconds(900));
        when(leaseService.extend(ENV, 240, "Extended via chat")).thenReturn(new LeaseService.ExtendOutcome(null, null));
        assertThat(service.execute("t", "user-1", 240).message()).contains("owners were asked to approve");

        when(leaseService.extend(ENV, 240, "Extended via chat"))
                .thenThrow(new ValidationException("An environment can run at most 4320 minutes in one lease, even with approval"));
        assertThatThrownBy(() -> service.execute("t", "user-1", 240))
                .isInstanceOf(ValidationException.class).hasMessageContaining("at most 4320 minutes");
        verify(audit).logFailure(eq("user-1"), any(), eq("chat_action"), eq("jti-1"), anyString(), contains("at most 4320"));

        assertThatThrownBy(() -> service.execute("t", "user-1", 90)).isInstanceOf(ValidationException.class);
    }

    @Test
    void anEndedLeaseCannotBeExtended() {
        link(ChatActionType.EXTEND_LEASE);
        when(leases.findActiveByEnvironmentId(ENV)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview("t")).isInstanceOf(ConflictException.class).hasMessageContaining("already ended");
        assertThatThrownBy(() -> service.execute("t", "user-1", null)).isInstanceOf(ConflictException.class);
        verify(tokens, never()).markUsed(anyString(), anyString());
        verify(leaseService, never()).extend(anyString(), anyInt(), any());
    }

    @Test
    void snoozePausesIdleStopWhileTheEnvironmentRuns() {
        link(ChatActionType.SNOOZE_IDLE_STOP);
        vmsIn(VmStatus.STOPPED, VmStatus.RUNNING);
        IdleStopSnooze snooze = new IdleStopSnooze();
        snooze.setSnoozedUntil(Timestamp.from(Instant.parse("2026-10-13T16:00:00Z")));
        when(snoozes.snooze(ENV, 4, "Snoozed via chat", "user-1")).thenReturn(snooze);

        assertThat(service.preview("t").choices()).containsExactly(1, 4, 8);
        assertThat(service.execute("t", "user-1", null).message())
                .isEqualTo("Idle auto-stop is paused for Dev QA until 16:00 UTC.");
        verify(tokens).markUsed("jti-1", "user-1");
    }

    @Test
    void aSnoozeAfterTheStopSaysItAlreadyStopped() {
        link(ChatActionType.SNOOZE_IDLE_STOP);
        vmsIn(VmStatus.STOPPED, VmStatus.STOPPED);
        assertThatThrownBy(() -> service.preview("t")).isInstanceOf(ConflictException.class)
                .hasMessage("Dev QA was already stopped");
        assertThatThrownBy(() -> service.execute("t", "user-1", 4)).isInstanceOf(ConflictException.class);
        verify(snoozes, never()).snooze(anyString(), anyInt(), any(), any());
        verify(tokens, never()).markUsed(anyString(), anyString());
    }
}
