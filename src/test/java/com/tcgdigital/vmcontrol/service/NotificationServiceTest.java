package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private EnvironmentAccessRepository accessRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;

    private NotificationService service;

    private Environment envA;
    private User envAdmin;      // globally env_admin=true, but only administers envA
    private User platformAdmin; // globally admin=true
    private User regularUser;  // direct USER-level access on envA
    private User groupScopedUser; // only a GROUP-scoped grant on envA

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, accessRepository, userRepository, emailService);

        envA = new Environment("env-A");

        envAdmin = user("user-envadmin", "envadmin@tcg.com", false, true);
        platformAdmin = user("user-admin", "admin@tcg.com", true, false);
        regularUser = user("user-regular", "regular@tcg.com", false, false);

        lenient().when(userRepository.findByAdminTrueAndIsActiveTrue()).thenReturn(List.of(platformAdmin));
        lenient().when(userRepository.findByEnvAdminTrueAndIsActiveTrue()).thenReturn(List.of(envAdmin));

        // envAdmin administers only envA: one query per environment returns its env-admins (E11-T08).
        EnvironmentAccess envAdminAccessOnA = EnvironmentAccess.create(envA, envAdmin, AccessLevel.ADMIN, null);
        lenient().when(accessRepository.findActiveEnvAdminGrantHolders(eq("env-A"), any(Timestamp.class)))
                .thenReturn(List.of(envAdmin));
        lenient().when(accessRepository.findActiveEnvAdminGrantHolders(eq("env-B"), any(Timestamp.class)))
                .thenReturn(List.of());

        // Direct access holders on envA: the env-admin's own ADMIN grant + a regular USER grant
        // + a user with ONLY a GROUP-scoped grant (must be excluded from environment broadcasts).
        EnvironmentAccess regularAccessOnA = EnvironmentAccess.create(envA, regularUser, AccessLevel.USER, null);
        groupScopedUser = user("user-groupscoped", "groupscoped@tcg.com", false, false);
        EnvironmentAccess groupAccessOnA = EnvironmentAccess.create(envA, groupScopedUser, AccessLevel.USER, null);
        groupAccessOnA.setScopeType(com.tcgdigital.vmcontrol.model.AccessScopeType.GROUP);
        groupAccessOnA.setScopeId("grp-1");
        lenient().when(accessRepository.findActiveAccessWithUsersByEnvironment("env-A"))
                .thenReturn(List.of(envAdminAccessOnA, regularAccessOnA, groupAccessOnA));
        lenient().when(accessRepository.findActiveAccessWithUsersByEnvironment("env-B"))
                .thenReturn(List.of());

        lenient().when(userRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private static User user(String id, String email, boolean admin, boolean envAdminFlag) {
        User u = new User(id);
        u.setEmail(email);
        u.setDisplayName(id);
        u.setIsActive(true);
        u.setAdmin(admin);
        u.setEnvAdmin(envAdminFlag);
        return u;
    }

    /** Every user a broadcast (one saveAll each, E11-T08) created a notification for. */
    @SuppressWarnings("unchecked")
    private List<String> broadcastUserIds() {
        ArgumentCaptor<Iterable<com.tcgdigital.vmcontrol.model.Notification>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository, org.mockito.Mockito.atLeast(0)).saveAll(saved.capture());
        java.util.List<String> ids = new java.util.ArrayList<>();
        saved.getAllValues().forEach(batch -> batch.forEach(n -> ids.add(n.getUserId())));
        return ids;
    }

    // ---- Phase 1: env-admin recipient scoping ----

    @Test
    void broadcast_excludesEnvAdminForEnvironmentTheyDoNotAdminister() {
        service.notifyLockAcquiredForEnvironment("env-B", "Env B", "user-regular", null);

        assertFalse(broadcastUserIds().contains("user-envadmin"));
    }

    @Test
    void broadcast_includesEnvAdminForEnvironmentTheyDoAdminister() {
        service.notifyLockAcquiredForEnvironment("env-A", "Env A", "user-regular", null);

        assertTrue(broadcastUserIds().contains("user-envadmin"));
    }

    @Test
    void broadcast_platformAdminAlwaysIncludedRegardlessOfEnvironment() {
        service.notifyLockAcquiredForEnvironment("env-B", "Env B", "user-regular", null);

        assertTrue(broadcastUserIds().contains("user-admin"));
    }

    @Test
    void broadcast_excludesUserWithOnlyAGroupScopedGrant() {
        service.notifyLockAcquiredForEnvironment("env-A", "Env A", "user-regular", null);

        List<String> ids = broadcastUserIds();
        assertFalse(ids.contains("user-groupscoped"));
        // sanity: the ENVIRONMENT-scoped regular user IS notified
        assertTrue(ids.contains("user-regular"));
    }

    // ---- E11-T08: 404 not 500, null actor, constant-query fan-out ----

    @Test
    void markingSomeoneElsesNotificationReadIsANotFound() {
        com.tcgdigital.vmcontrol.model.Notification theirs = new com.tcgdigital.vmcontrol.model.Notification();
        theirs.setNotificationId("n-1");
        theirs.setUserId("user-admin");
        when(notificationRepository.findById("n-1")).thenReturn(Optional.of(theirs));

        org.junit.jupiter.api.Assertions.assertThrows(com.tcgdigital.vmcontrol.exception.ResourceNotFoundException.class,
                () -> service.markAsRead("n-1", "user-regular"));
        org.junit.jupiter.api.Assertions.assertThrows(com.tcgdigital.vmcontrol.exception.ResourceNotFoundException.class,
                () -> service.markAsRead("missing", "user-regular"));
    }

    @Test
    void aSystemInitiatedBroadcastNamesAutomationWithoutLookingUpANullUser() {
        service.notifyLockAcquiredForEnvironment("env-A", "Env A", null, null);

        verify(userRepository, never()).findById(null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<com.tcgdigital.vmcontrol.model.Notification>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository).saveAll(saved.capture());
        saved.getValue().forEach(n -> assertTrue(n.getTitle().contains("Automation") || n.getMessage().contains("Automation"),
                "names the automation: " + n.getTitle()));
    }

    @Test
    void aBroadcastSavesOnceAndResolvesEnvAdminsInOneQuery() {
        service.notifyLockAcquiredForEnvironment("env-A", "Env A", "user-regular", null);

        verify(notificationRepository, org.mockito.Mockito.times(1)).saveAll(any());
        verify(notificationRepository, never()).save(any());
        verify(accessRepository, org.mockito.Mockito.times(1)).findActiveEnvAdminGrantHolders(eq("env-A"), any(Timestamp.class));
        verify(accessRepository, never()).findByUserWithMinAccessLevel(anyString(), any(), any());
    }

    // ---- Phase 3: per-type email dispatch ----

    @Test
    void notifyAccessGranted_sendsNoEmailWhenFlagOff() {
        ReflectionTestUtils.setField(service, "emailAccessGrantedEnabled", false);

        service.notifyAccessGranted("user-regular", "Env A", "env-A");

        verify(notificationRepository).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void notifyAccessGranted_sendsEmailWhenFlagOn() {
        ReflectionTestUtils.setField(service, "emailAccessGrantedEnabled", true);
        when(userRepository.findById("user-regular")).thenReturn(Optional.of(regularUser));

        service.notifyAccessGranted("user-regular", "Env A", "env-A");

        verify(notificationRepository).save(any());
        verify(emailService).sendHtml(eq(List.of("regular@tcg.com")), anyString(), anyString(), eq(null), eq(null));
    }

    @Test
    void notifyAccessRequestedForReviewers_emailGoesToAllAdminOnly_notEnvAdmin() {
        ReflectionTestUtils.setField(service, "emailAccessRequestedEnabled", true);

        service.notifyAccessRequestedForReviewers("env-A", "Env A", "user-regular", "req-1", "user");

        // Bell fires for the full reviewer set (env-admin included, since they administer env-A)
        verify(notificationRepository).save(argThat(n -> "user-envadmin".equals(n.getUserId())));
        verify(notificationRepository).save(argThat(n -> "user-admin".equals(n.getUserId())));

        // Email is narrower: All Admin only, not env-admin.
        ArgumentCaptor<List<String>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtml(addressesCaptor.capture(), anyString(), anyString(), eq(null), eq(null));
        assertEquals(List.of("admin@tcg.com"), addressesCaptor.getValue());
    }

    @Test
    void notifyOperationFailedForEnvironment_emailExcludesEnvAdmin() {
        ReflectionTestUtils.setField(service, "emailOperationFailedEnabled", true);

        service.notifyOperationFailedForEnvironment("env-A", "Env A", "user-regular", "START", "boom");

        ArgumentCaptor<List<String>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService, atLeastOnce()).sendHtml(addressesCaptor.capture(), anyString(), anyString(), eq(null), eq(null));

        List<String> allEmailedAddresses = addressesCaptor.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(allEmailedAddresses.contains("regular@tcg.com"), "actor should be emailed");
        assertTrue(allEmailedAddresses.contains("admin@tcg.com"), "platform admin should be emailed");
        assertFalse(allEmailedAddresses.contains("envadmin@tcg.com"), "env admin must be excluded from this email");
    }

    @Test
    void notifyLockBrokenForEnvironment_emailIncludesEnvAdmin() {
        ReflectionTestUtils.setField(service, "emailLockBrokenEnabled", true);

        service.notifyLockBrokenForEnvironment("env-A", "Env A", "user-admin", "user-regular", "policy");

        ArgumentCaptor<List<String>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService, atLeastOnce()).sendHtml(addressesCaptor.capture(), anyString(), anyString(), eq(null), eq(null));

        List<String> allEmailedAddresses = addressesCaptor.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(allEmailedAddresses.contains("envadmin@tcg.com"), "env admin must be included for Lock Broken");
    }

    @Test
    void notifyStopEnvironment_emailsRecipientsWithBroadcastContext_noFlagRequired() {
        // Unlike the other email types, this one is NOT gated behind a notification.email.*
        // flag — the admin's button click is itself the opt-in — so no ReflectionTestUtils
        // setField is needed here.
        int emailed = service.notifyStopEnvironment("env-A", "Env A", "user-admin", "scheduled maintenance");

        ArgumentCaptor<List<String>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService, atLeastOnce()).sendHtml(addressesCaptor.capture(), anyString(), anyString(),
                eq(null), eq(null), eq("STOP_ENVIRONMENT_BROADCAST"), eq("env-A"), eq("user-admin"));

        List<String> allEmailedAddresses = addressesCaptor.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(allEmailedAddresses.contains("admin@tcg.com"), "platform admin must be notified");
        assertTrue(allEmailedAddresses.contains("envadmin@tcg.com"), "administering env admin must be notified");
        assertTrue(emailed > 0, "should report a positive emailed count");
    }

    @Test
    void notifyAccessExpiring_emailGoesToUserAndAdministeringEnvAdmins() {
        ReflectionTestUtils.setField(service, "emailAccessExpiringEnabled", true);
        Timestamp windowStart = new Timestamp(System.currentTimeMillis());
        when(notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                eq("user-regular"), any(), eq("ACCESS"), eq("access-1"), eq(windowStart))).thenReturn(false);
        when(userRepository.findById("user-regular")).thenReturn(Optional.of(regularUser));

        service.notifyAccessExpiring("user-regular", "Env A", "env-A", "access-1", null, windowStart);

        ArgumentCaptor<List<String>> addressesCaptor = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtml(addressesCaptor.capture(), anyString(), anyString(), eq(null), eq(null));
        assertTrue(addressesCaptor.getValue().contains("regular@tcg.com"));
        assertTrue(addressesCaptor.getValue().contains("envadmin@tcg.com"));
    }

    @Test
    void notifyAccessExpiring_doesNotResendEmailWhenBellNotificationAlreadyExists() {
        ReflectionTestUtils.setField(service, "emailAccessExpiringEnabled", true);
        Timestamp windowStart = new Timestamp(System.currentTimeMillis());
        when(notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                eq("user-regular"), any(), eq("ACCESS"), eq("access-1"), eq(windowStart))).thenReturn(true);

        service.notifyAccessExpiring("user-regular", "Env A", "env-A", "access-1", null, windowStart);

        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }
}
