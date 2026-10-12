package com.tcgdigital.vmcontrol.service.provisioning;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.LeaseExtendedEvent;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The end of a provisioned environment (E32-T05): warnings once, capped extension, destroy only after the engine says so. */
class ProvisioningDestroyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final ProvisioningRequestRepository repository = mock(ProvisioningRequestRepository.class);
    private final TemplateService templates = mock(TemplateService.class);
    private final ProvisioningEngine engine = mock(ProvisioningEngine.class);
    private final EnvironmentService environments = mock(EnvironmentService.class);
    private final EnvironmentAccessService access = mock(EnvironmentAccessService.class);
    private final OperationExecutionRepository executions = mock(OperationExecutionRepository.class);
    private final SecurityService security = mock(SecurityService.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private ProvisioningDestroyService service;
    private EnvironmentTemplate template;
    private ProvisioningRequest request;

    @BeforeEach
    void setUp() {
        when(engine.type()).thenReturn(ProvisioningEngineType.SERVICE_CATALOG);
        service = new ProvisioningDestroyService(repository, templates, new ProvisioningEngineRegistry(List.of(engine)),
                environments, access, executions, security, audit);
        service.setNotificationService(notifications);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        when(templates.isEnabled()).thenReturn(true);
        template = new EnvironmentTemplate();
        template.setTemplateId("t-1");
        template.setEngine(ProvisioningEngineType.SERVICE_CATALOG);
        template.setMaxTtlHours(168);
        when(templates.find("t-1")).thenReturn(template);
        request = new ProvisioningRequest();
        request.setRequestId("r-1");
        request.setTemplateId("t-1");
        request.setRequestedByUserId("u-1");
        request.setEnvironmentName("feature-x");
        request.setEnvironmentId("env-1");
        request.setEngineResourceId("pp-1");
        request.setStatus(ProvisioningStatus.READY);
        ReflectionTestUtils.setField(request, "createdAt", Timestamp.from(NOW.minus(Duration.ofHours(48))));
        request.setDestroyAt(Timestamp.from(NOW.plus(Duration.ofMinutes(50))));
        when(repository.findById("r-1")).thenReturn(Optional.of(request));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void anOwnerIsWarnedOnceAnHourBefore() {
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.READY)).thenReturn(List.of(request));

        assertThat(service.warn()).isEqualTo(1);
        assertThat(service.warn()).isZero();

        verify(notifications, times(1)).notifyEnvironmentDestroyWarning(eq("u-1"), eq("env-1"), eq("feature-x"), any(), eq("1 hour"));
        assertThat(request.isWarnedHour()).isTrue();
    }

    @Test
    void extensionIsCappedAtTheTemplatesMaximumUnlessAnAdminExtends() {
        // created 48 h ago, maximum 168 h: the latest destroy time is 120 h from now
        assertThatThrownBy(() -> service.extendTtl("r-1", 200, "u-1")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("at most 168 hours");

        service.extendTtl("r-1", 24, "u-1");
        assertThat(request.getDestroyAt()).isEqualTo(Timestamp.from(NOW.plus(Duration.ofMinutes(50)).plus(Duration.ofHours(24))));
        assertThat(request.isWarnedHour()).isFalse();
        verify(audit).logAction(eq("u-1"), eq(AuditAction.PROVISIONING_TTL_EXTENDED), anyString(), eq("r-1"), anyString(), anyString());

        when(security.isAdmin()).thenReturn(true);
        service.extendTtl("r-1", 500, "admin-1");
        assertThat(request.getDestroyAt().toInstant()).isAfter(NOW.plus(Duration.ofHours(500)));
    }

    @Test
    void extendingTheLeaseMovesTheDestroyTimeWithinTheMaximum() {
        when(repository.findByEnvironmentIdAndStatus("env-1", ProvisioningStatus.READY)).thenReturn(Optional.of(request));

        service.onLeaseExtended(new LeaseExtendedEvent("env-1", NOW.plus(Duration.ofHours(10)), "u-1"));
        assertThat(request.getDestroyAt()).isEqualTo(Timestamp.from(NOW.plus(Duration.ofHours(10))));

        service.onLeaseExtended(new LeaseExtendedEvent("env-1", NOW.plus(Duration.ofHours(500)), "u-1"));
        assertThat(request.getDestroyAt()).isEqualTo(Timestamp.from(NOW.plus(Duration.ofHours(120)))); // capped
    }

    @Test
    void withDestroyOffNothingIsDestroyed() {
        request.setDestroyAt(Timestamp.from(NOW.minus(Duration.ofMinutes(1))));

        assertThat(service.destroyDue()).isZero();

        verify(engine, never()).destroy(any(), any());
    }

    @Test
    void theEnvironmentIsDeactivatedOnlyAfterTheEngineReportsTheDestroy() {
        ReflectionTestUtils.setField(service, "destroyEnabled", true);
        request.setDestroyAt(Timestamp.from(NOW.minus(Duration.ofMinutes(1))));
        when(repository.findByDestroyAtBeforeAndStatus(any(), eq(ProvisioningStatus.READY))).thenReturn(List.of(request));
        when(repository.moveIfStatus(eq("r-1"), eq(ProvisioningStatus.READY), eq(ProvisioningStatus.DESTROYING), any())).thenReturn(1);
        when(engine.destroy(request, template)).thenReturn("rec-d");

        assertThat(service.destroyDue()).isEqualTo(1);
        assertThat(request.getDestroyRecordId()).isEqualTo("rec-d");
        verify(environments, never()).deactivateEnvironment(anyString(), anyString());

        request.setStatus(ProvisioningStatus.DESTROYING);
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.DESTROYING)).thenReturn(List.of(request));
        when(engine.poll(template, "rec-d")).thenReturn(new ProvisioningProgress(ProvisioningProgress.State.IN_PROGRESS, "pp-1", "IN_PROGRESS"));
        assertThat(service.pollDestroying()).isZero();
        verify(environments, never()).deactivateEnvironment(anyString(), anyString());

        when(engine.poll(template, "rec-d")).thenReturn(new ProvisioningProgress(ProvisioningProgress.State.SUCCEEDED, "pp-1", "Succeeded"));
        assertThat(service.pollDestroying()).isEqualTo(1);
        InOrder order = inOrder(environments, access);
        order.verify(environments).deactivateEnvironment("env-1", "u-1");
        order.verify(access).revokeAccess("env-1", "u-1", "u-1");
        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.DESTROYED);
        verify(notifications).notifyEnvironmentDestroyed("u-1", "env-1", "feature-x", true, null);
    }

    @Test
    void aFailedDestroyKeepsTheEnvironmentAndTellsTheAdminsOnce() {
        request.setStatus(ProvisioningStatus.DESTROYING);
        request.setDestroyRecordId("rec-d");
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.DESTROYING)).thenReturn(List.of(request));
        when(engine.poll(template, "rec-d")).thenReturn(new ProvisioningProgress(ProvisioningProgress.State.FAILED, "pp-1", "DELETE_FAILED"));

        service.pollDestroying();
        service.pollDestroying();

        verify(environments, never()).deactivateEnvironment(anyString(), anyString());
        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.DESTROYING);
        verify(notifications, times(1)).notifyEnvironmentDestroyed("u-1", "env-1", "feature-x", false, "DELETE_FAILED");
    }

    @Test
    void aManualDestroyNeedsTheConfirmation() {
        assertThatThrownBy(() -> service.destroyNow("r-1", "u-1", false)).isInstanceOf(ValidationException.class);
        when(repository.moveIfStatus(eq("r-1"), eq(ProvisioningStatus.READY), eq(ProvisioningStatus.DESTROYING), any())).thenReturn(1);
        when(engine.destroy(request, template)).thenReturn("rec-m");

        service.destroyNow("r-1", "u-1", true);

        verify(engine).destroy(request, template);
    }
}
