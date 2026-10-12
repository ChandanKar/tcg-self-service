package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Building approved requests (E32-T04): start once, success creates and grants, failure and time-out. */
class ProvisioningExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final ProvisioningRequestRepository repository = mock(ProvisioningRequestRepository.class);
    private final TemplateService templates = mock(TemplateService.class);
    private final ProvisioningEngine engine = mock(ProvisioningEngine.class);
    private final EnvironmentService environments = mock(EnvironmentService.class);
    private final EnvironmentAccessService access = mock(EnvironmentAccessService.class);
    private final VmDiscoveryService discovery = mock(VmDiscoveryService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private ProvisioningExecutor executor;
    private EnvironmentTemplate template;
    private ProvisioningRequest request;

    @BeforeEach
    void setUp() {
        when(engine.type()).thenReturn(ProvisioningEngineType.SERVICE_CATALOG);
        executor = new ProvisioningExecutor(repository, templates, new ProvisioningEngineRegistry(List.of(engine)), environments,
                access, discovery, users, audit, new ObjectMapper(), TransactionOperations.withoutTransaction());
        executor.setNotificationService(notifications);
        executor.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        when(templates.isEnabled()).thenReturn(true);
        template = new EnvironmentTemplate();
        template.setTemplateId("t-1");
        template.setName("Dev stack");
        template.setEngine(ProvisioningEngineType.SERVICE_CATALOG);
        template.setRegion("us-east-1");
        when(templates.find("t-1")).thenReturn(template);
        request = new ProvisioningRequest();
        request.setRequestId("r-1");
        request.setTemplateId("t-1");
        request.setRequestedByUserId("u-1");
        request.setReviewedByUserId("approver-1");
        request.setEnvironmentName("feature-x");
        request.setTtlHours(48);
        when(repository.findById("r-1")).thenReturn(Optional.of(request));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        User u = new User();
        u.setUserId("u-1");
        u.setEmail("dev@example.com");
        when(users.findById("u-1")).thenReturn(Optional.of(u));
    }

    @Test
    void anApprovedRequestIsStartedOnceAndItsRecordKept() {
        request.setStatus(ProvisioningStatus.APPROVED);
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.APPROVED)).thenReturn(List.of(request));
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.PROVISIONING)).thenReturn(List.of());
        when(repository.moveIfStatus(eq("r-1"), eq(ProvisioningStatus.APPROVED), eq(ProvisioningStatus.PROVISIONING), any()))
                .thenReturn(1, 0);
        when(engine.provision(request, template)).thenReturn("rec-1");

        executor.run();
        executor.run(); // another pass (or server) finds it already taken

        verify(engine, org.mockito.Mockito.times(1)).provision(any(), any());
        assertThat(request.getEngineRecordId()).isEqualTo("rec-1");
        verify(audit).logAction(eq("u-1"), eq(AuditAction.PROVISIONING_STARTED), eq("provisioning_request"), eq("r-1"), eq("feature-x"), anyString());
    }

    private void provisioning(ProvisioningProgress progress) {
        request.setStatus(ProvisioningStatus.PROVISIONING);
        request.setEngineRecordId("rec-1");
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.APPROVED)).thenReturn(List.of());
        when(repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.PROVISIONING)).thenReturn(List.of(request));
        when(engine.poll(template, "rec-1")).thenReturn(progress);
    }

    @Test
    void successCreatesTheEnvironmentRegistersItsVmsAndGrantsTheRequester() {
        provisioning(new ProvisioningProgress(ProvisioningProgress.State.SUCCEEDED, "pp-1", "Succeeded"));
        Environment env = new Environment();
        env.setEnvironmentId("env-9");
        env.setName("feature-x");
        env.setDisplayName("feature-x");
        when(environments.createEnvironment(any(CreateEnvironmentDTO.class), eq("u-1"))).thenReturn(env);

        executor.run();

        ArgumentCaptor<CreateEnvironmentDTO> dto = ArgumentCaptor.forClass(CreateEnvironmentDTO.class);
        verify(environments).createEnvironment(dto.capture(), eq("u-1"));
        assertThat(dto.getValue().getName()).isEqualTo("feature-x");
        assertThat(dto.getValue().getMetadata()).contains("\"region\":\"us-east-1\"").contains("r-1");
        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(request.getEnvironmentId()).isEqualTo("env-9");
        assertThat(request.getEngineResourceId()).isEqualTo("pp-1");
        assertThat(request.getDestroyAt()).isEqualTo(Timestamp.from(NOW.plus(Duration.ofHours(48))));
        InOrder order = inOrder(environments, discovery, access);
        order.verify(environments).createEnvironment(any(), eq("u-1"));
        order.verify(discovery).discoverEnvironmentVms(env);
        ArgumentCaptor<GrantAccessDTO> grant = ArgumentCaptor.forClass(GrantAccessDTO.class);
        order.verify(access).grantAccess(eq("env-9"), eq("approver-1"), grant.capture());
        assertThat(grant.getValue().getUserEmail()).isEqualTo("dev@example.com");
        assertThat(grant.getValue().getAccessLevel()).isEqualTo(AccessLevel.ADMIN);
        assertThat(grant.getValue().getDurationDays()).isEqualTo(2);
        verify(notifications).notifyProvisioningOutcome(eq("u-1"), eq("r-1"), eq("feature-x"), eq("env-9"), eq(true), anyString());
    }

    @Test
    void aFailedBuildEndsFailedWithAwsMessageAndCreatesNothing() {
        provisioning(new ProvisioningProgress(ProvisioningProgress.State.FAILED, null, "Subnet subnet-x does not exist"));

        executor.run();

        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(request.getErrorMessage()).isEqualTo("Subnet subnet-x does not exist");
        verify(environments, never()).createEnvironment(any(), anyString());
        verify(notifications).notifyProvisioningOutcome(eq("u-1"), eq("r-1"), eq("feature-x"), isNull(), eq(false),
                eq("Subnet subnet-x does not exist"));
    }

    @Test
    void aBuildRunningLongerThanTheTimeoutFails() {
        provisioning(new ProvisioningProgress(ProvisioningProgress.State.IN_PROGRESS, "pp-1", "IN_PROGRESS"));
        request.setStatus(ProvisioningStatus.PROVISIONING);
        org.springframework.test.util.ReflectionTestUtils.setField(request, "updatedAt", Timestamp.from(NOW.minus(Duration.ofMinutes(61))));

        executor.run();

        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.FAILED);
        assertThat(request.getErrorMessage()).contains("Timed out after 60 minutes").contains("pp-1");
    }

    @Test
    void stillRunningWithinTheTimeoutWaits() {
        provisioning(new ProvisioningProgress(ProvisioningProgress.State.IN_PROGRESS, "pp-1", "IN_PROGRESS"));
        org.springframework.test.util.ReflectionTestUtils.setField(request, "updatedAt", Timestamp.from(NOW.minus(Duration.ofMinutes(5))));

        assertThat(executor.run()).isZero();
        assertThat(request.getStatus()).isEqualTo(ProvisioningStatus.PROVISIONING);
    }

    @Test
    void turnedOffNothingRuns() {
        when(templates.isEnabled()).thenReturn(false);

        assertThat(executor.run()).isZero();
        verify(repository, never()).findByStatusOrderByCreatedAtAsc(any());
    }
}
