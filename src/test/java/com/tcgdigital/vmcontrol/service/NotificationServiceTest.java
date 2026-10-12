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


    /** Settings this test turns on or off (E29-T02). */

    private final java.util.Map<String, String> settingValues = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, accessRepository, userRepository, emailService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settings",
                com.tcgdigital.vmcontrol.service.settings.TestSettings.of(settingValues));

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
        settingValues.put("notification.email.access-granted.enabled", "false");

        service.notifyAccessGranted("user-regular", "Env A", "env-A");

        verify(notificationRepository).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void notifyAccessGranted_sendsEmailWhenFlagOn() {
        settingValues.put("notification.email.access-granted.enabled", "true");
        when(userRepository.findById("user-regular")).thenReturn(Optional.of(regularUser));

        service.notifyAccessGranted("user-regular", "Env A", "env-A");

        verify(notificationRepository).save(any());
        verify(emailService).sendHtml(eq(List.of("regular@tcg.com")), anyString(), anyString(), eq(null), eq(null));
    }

    @Test
    void notifyAccessRequestedForReviewers_emailGoesToAllAdminOnly_notEnvAdmin() {
        settingValues.put("notification.email.access-requested.enabled", "true");

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
    void theOperationFailedEmailFollowsTheSettingWithoutARestart() {
        // E29-T02: the toggle is read at each call.
        service.notifyOperationFailedForEnvironment("env-A", "Env A", "user-regular", "START", "boom");
        verify(emailService, never()).sendHtml(anyList(), anyString(), anyString(), eq(null), eq(null));

        settingValues.put("notification.email.operation-failed.enabled", "true");
        service.notifyOperationFailedForEnvironment("env-A", "Env A", "user-regular", "START", "boom");
        verify(emailService, atLeastOnce()).sendHtml(anyList(), anyString(), anyString(), eq(null), eq(null));

        settingValues.put("notification.email.operation-failed.enabled", "false");
        org.mockito.Mockito.clearInvocations(emailService);
        service.notifyOperationFailedForEnvironment("env-A", "Env A", "user-regular", "START", "boom");
        verify(emailService, never()).sendHtml(anyList(), anyString(), anyString(), eq(null), eq(null));
    }

    @Test
    void notifyOperationFailedForEnvironment_emailExcludesEnvAdmin() {
        settingValues.put("notification.email.operation-failed.enabled", "true");

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
        settingValues.put("notification.email.lock-broken.enabled", "true");

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
        settingValues.put("notification.email.access-expiring.enabled", "true");
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

    // ---- per-user preferences (E29-T05) ----

    private com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository usePreferences() {
        com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository repo =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "preferences", new NotificationPreferenceService(repo));
        return repo;
    }

    @Test
    void aUserWhoTurnedOffInAppOperationCompletedGetsNoBellItemOthersStillDo() {
        com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository repo = usePreferences();
        com.tcgdigital.vmcontrol.model.UserNotificationPreference off =
                new com.tcgdigital.vmcontrol.model.UserNotificationPreference("user-regular", com.tcgdigital.vmcontrol.model.NotificationType.OPERATION_COMPLETED);
        off.setInAppEnabled(false);
        when(repo.findByUserIdInAndNotificationType(anyList(), eq(com.tcgdigital.vmcontrol.model.NotificationType.OPERATION_COMPLETED)))
                .thenReturn(List.of(off));

        service.notifyOperationCompletedForEnvironment("env-A", "Env A", "user-admin", "START", 1, 0);

        List<String> ids = broadcastUserIds();
        assertFalse(ids.contains("user-regular"));
        assertTrue(ids.contains("user-admin"));
        assertTrue(ids.contains("user-envadmin"));
        verify(repo, times(1)).findByUserIdInAndNotificationType(anyList(), any()); // one query for the broadcast
    }

    @Test
    void aUserWhoTurnedOffTheAccessExpiringEmailStillGetsTheBellOnly() {
        com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository repo = usePreferences();
        com.tcgdigital.vmcontrol.model.UserNotificationPreference off =
                new com.tcgdigital.vmcontrol.model.UserNotificationPreference("user-regular", com.tcgdigital.vmcontrol.model.NotificationType.ACCESS_EXPIRING);
        off.setEmailEnabled(false);
        when(repo.findByUserIdInAndNotificationType(anyList(), eq(com.tcgdigital.vmcontrol.model.NotificationType.ACCESS_EXPIRING)))
                .thenReturn(List.of(off));
        settingValues.put("notification.email.access-expiring.enabled", "true");
        Timestamp windowStart = new Timestamp(System.currentTimeMillis());
        when(userRepository.findById("user-regular")).thenReturn(Optional.of(regularUser));

        service.notifyAccessExpiring("user-regular", "Env A", "env-A", "access-1", null, windowStart);

        verify(notificationRepository).save(argThat(n -> "user-regular".equals(n.getUserId()))); // the bell
        ArgumentCaptor<List<String>> addresses = ArgumentCaptor.forClass(List.class);
        verify(emailService).sendHtml(addresses.capture(), anyString(), anyString(), eq(null), eq(null));
        assertFalse(addresses.getValue().contains("regular@tcg.com"));
        assertTrue(addresses.getValue().contains("envadmin@tcg.com"));
    }

    @Test
    void notifyAccessExpiring_doesNotResendEmailWhenBellNotificationAlreadyExists() {
        settingValues.put("notification.email.access-expiring.enabled", "true");
        Timestamp windowStart = new Timestamp(System.currentTimeMillis());
        when(notificationRepository.existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                eq("user-regular"), any(), eq("ACCESS"), eq("access-1"), eq(windowStart))).thenReturn(true);

        service.notifyAccessExpiring("user-regular", "Env A", "env-A", "access-1", null, windowStart);

        verify(notificationRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    // ============= Chat (E24-T03) =============

    @Test
    void environmentEventsGoToChatInTheThirdPerson() {
        com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat =
                mock(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.class);
        service.setChatDispatch(chat);
        when(userRepository.findById("user-regular")).thenReturn(Optional.of(regularUser));

        service.notifyOperationFailedForEnvironment("env-A", "Env A", "user-regular", "STOP", "boom");
        service.notifyLockBrokenForEnvironment("env-A", "Env A", "user-regular", "user-regular", "stuck");
        service.notifyStopEnvironment("env-A", "Env A", "user-regular", "maintenance");
        service.notifyIdleStopForEnvironment("env-A", "Env A", 90, 2);
        service.notifyStateDriftDetected("env-A", "Env A", "web-1", "RUNNING", "STOPPED", "vm-1");

        ArgumentCaptor<com.tcgdigital.vmcontrol.model.NotificationType> types =
                ArgumentCaptor.forClass(com.tcgdigital.vmcontrol.model.NotificationType.class);
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(chat, times(4)).notify(eq("env-A"), types.capture(), any(), titles.capture(), texts.capture(), any());
        assertEquals(List.of(com.tcgdigital.vmcontrol.model.NotificationType.OPERATION_FAILED,
                com.tcgdigital.vmcontrol.model.NotificationType.LOCK_BROKEN,
                com.tcgdigital.vmcontrol.model.NotificationType.IDLE_AUTO_STOPPED,
                com.tcgdigital.vmcontrol.model.NotificationType.STATE_DRIFT_DETECTED), types.getAllValues());
        // Stop notices carry no Stop-now button by default (chat.actions.stop-enabled=false).
        verify(chat).notifyWithActions(eq("env-A"), eq(com.tcgdigital.vmcontrol.model.NotificationType.ENVIRONMENT_STOP_NOTICE),
                any(), argThat(t -> !t.startsWith("You")), anyString(), eq(null), eq(List.of()), eq(null));
        for (String text : titles.getAllValues()) {
            assertFalse(text.matches("(?i)^(you|your)\b.*"), text);
        }
        for (String text : texts.getAllValues()) {
            assertFalse(text.matches("(?is).*\b(you|your|you're)\b.*"), text);
        }
        assertTrue(texts.getAllValues().get(0).contains("Reason: boom"));
    }

    @Test
    void anAnomalyGetsACreateTicketButtonOnlyWhenJiraIsOn() {
        com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat =
                mock(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.class);
        service.setChatDispatch(chat);
        service.notifyCostAnomaly("env-A", "Env A", new java.math.BigDecimal("12.50"), null, "EC2", "an-1");
        verify(chat).notifyWithActions(eq("env-A"), eq(com.tcgdigital.vmcontrol.model.NotificationType.COST_ANOMALY), any(),
                anyString(), anyString(), eq(null), eq(List.of()), eq("COST_ANOMALY:an-1"));

        ReflectionTestUtils.setField(service, "jiraEnabled", true);
        service.notifyCostAnomaly("env-A", "Env A", new java.math.BigDecimal("12.50"), null, "EC2", "an-2");
        ArgumentCaptor<List<com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec>> actions =
                ArgumentCaptor.forClass(List.class);
        verify(chat).notifyWithActions(eq("env-A"), any(), any(), anyString(), anyString(), eq(null), actions.capture(),
                eq("COST_ANOMALY:an-2"));
        assertEquals(com.tcgdigital.vmcontrol.model.ChatActionType.CREATE_JIRA_TICKET, actions.getValue().get(0).type());
        assertEquals("an-2", actions.getValue().get(0).targetId());
    }

    @Test
    void anAccessRequestGoesToChatWithAnApproveButton() {
        com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat =
                mock(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.class);
        service.setChatDispatch(chat);
        service.notifyAccessRequestedForReviewers("env-A", "Env A", "user-regular", "req-1", "user");
        ArgumentCaptor<List<com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec>> actions =
                ArgumentCaptor.forClass(List.class);
        verify(chat).notifyWithActions(eq("env-A"), eq(com.tcgdigital.vmcontrol.model.NotificationType.ACCESS_REQUESTED),
                any(), anyString(), anyString(), eq("#/pending-requests"), actions.capture(), eq(null));
        assertEquals(com.tcgdigital.vmcontrol.model.ChatActionType.APPROVE_ACCESS_REQUEST, actions.getValue().get(0).type());
        assertEquals("req-1", actions.getValue().get(0).targetId());
    }

    @Test
    void aLeaseWarningGoesToChatOncePerLeaseEnd() {
        com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat =
                mock(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.class);
        service.setChatDispatch(chat);
        Timestamp ends = new Timestamp(1_800_000_000_000L);
        service.notifyLeaseExpiring("user-regular", "env-A", "Env A", ends, 30, new Timestamp(1_799_999_100_000L));
        ArgumentCaptor<List<com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.ActionSpec>> actions =
                ArgumentCaptor.forClass(List.class);
        // The key carries the warning window, so the 60, 15 and 5-minute warnings each reach chat once.
        verify(chat).notifyWithActions(eq("env-A"), eq(com.tcgdigital.vmcontrol.model.NotificationType.LEASE_EXPIRING), any(),
                anyString(), anyString(), eq(null), actions.capture(), eq("LEASE_EXPIRING:env-A:1800000000000:1799999100000"));
        assertEquals(com.tcgdigital.vmcontrol.model.ChatActionType.EXTEND_LEASE, actions.getValue().get(0).type());
        assertEquals("Extend 2h", actions.getValue().get(0).label());
        assertTrue(actions.getValue().get(0).ttl().toMinutes() >= 5);
    }

    @Test
    void chatFailuresNeverBreakNotifications() {
        com.tcgdigital.vmcontrol.service.chat.ChatDispatchService chat =
                mock(com.tcgdigital.vmcontrol.service.chat.ChatDispatchService.class);
        doThrow(new IllegalStateException("down")).when(chat).notify(any(), any(), any(), any(), any(), any());
        service.setChatDispatch(chat);
        assertDoesNotThrow(() -> service.notifyIdleStopForEnvironment("env-A", "Env A", 90, 2));
        verify(notificationRepository).saveAll(any());
    }
}
