package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.NotificationPreferenceDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.UserNotificationPreference;
import com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository;
import com.tcgdigital.vmcontrol.service.settings.TestSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Notification preferences (E29-T05): defaults, the mandatory bell, bulk filtering and email availability. */
class NotificationPreferenceServiceTest {

    private UserNotificationPreferenceRepository repository;
    private NotificationPreferenceService service;
    private final Map<String, String> settings = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(UserNotificationPreferenceRepository.class);
        when(repository.findByUserId(any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new NotificationPreferenceService(repository);
        ReflectionTestUtils.setField(service, "settings", TestSettings.of(settings));
    }

    private static UserNotificationPreference pref(String user, NotificationType type, boolean inApp, boolean email) {
        UserNotificationPreference p = new UserNotificationPreference(user, type);
        p.setInAppEnabled(inApp);
        p.setEmailEnabled(email);
        return p;
    }

    private static User user(String id) {
        User u = new User(id);
        u.setEmail(id + "@x");
        return u;
    }

    @Test
    void everythingIsOnUntilTurnedOffAndRequiredBellsAreLocked() {
        List<NotificationPreferenceDTO> list = service.list("u1");

        assertThat(list).hasSize(NotificationType.values().length);
        assertThat(list).allMatch(p -> p.inApp() && p.email());
        NotificationPreferenceDTO lockBroken = list.stream().filter(p -> p.type().equals("LOCK_BROKEN")).findFirst().orElseThrow();
        assertThat(lockBroken.inAppLocked()).isTrue();
        assertThat(lockBroken.label()).isEqualTo("Lock broken");
        assertThat(lockBroken.category()).isEqualTo("Locks");
    }

    @Test
    void aRequiredBellCannotBeTurnedOffButItsEmailCan() {
        List<NotificationPreferenceDTO> after = service.update("u1", List.of(
                new NotificationPreferenceDTO.Update("LOCK_BROKEN", false, false),
                new NotificationPreferenceDTO.Update("OPERATION_COMPLETED", false, null)));

        NotificationPreferenceDTO lockBroken = after.stream().filter(p -> p.type().equals("LOCK_BROKEN")).findFirst().orElseThrow();
        verify(repository).save(argThat(p -> p.getNotificationType() == NotificationType.LOCK_BROKEN && p.isInAppEnabled() && !p.isEmailEnabled()));
        verify(repository).save(argThat(p -> p.getNotificationType() == NotificationType.OPERATION_COMPLETED && !p.isInAppEnabled() && p.isEmailEnabled()));
        assertThat(lockBroken.inApp()).isTrue();
        assertThatThrownBy(() -> service.update("u1", List.of(new NotificationPreferenceDTO.Update("NOPE", true, true))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aBroadcastIsFilteredWithOneQuery() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) ids.add("u" + i);
        when(repository.findByUserIdInAndNotificationType(anyCollection(), eq(NotificationType.OPERATION_COMPLETED)))
                .thenReturn(List.of(pref("u7", NotificationType.OPERATION_COMPLETED, false, true)));

        Set<String> wanted = service.inAppRecipients(NotificationType.OPERATION_COMPLETED, ids);

        assertThat(wanted).hasSize(199).doesNotContain("u7");
        verify(repository, times(1)).findByUserIdInAndNotificationType(anyCollection(), any());

        // Mandatory types skip the lookup entirely.
        assertThat(service.inAppRecipients(NotificationType.LOCK_BROKEN, ids)).hasSize(200);
        verify(repository, times(1)).findByUserIdInAndNotificationType(anyCollection(), any());
    }

    @Test
    void emailRecipientsDropThoseWhoTurnedItOff() {
        when(repository.findByUserIdInAndNotificationType(anyCollection(), eq(NotificationType.ACCESS_EXPIRING)))
                .thenReturn(List.of(pref("u2", NotificationType.ACCESS_EXPIRING, true, false)));

        assertThat(service.emailRecipients(NotificationType.ACCESS_EXPIRING, List.of(user("u1"), user("u2"))))
                .extracting(User::getUserId).containsExactly("u1");
    }

    @Test
    void emailIsAvailableOnlyWhenThePlatformSendsIt() {
        settings.put("notification.email.enabled", "true");
        settings.put("notification.email.lock-broken.enabled", "true");

        List<NotificationPreferenceDTO> list = service.list("u1");

        assertThat(find(list, "LOCK_BROKEN").emailAvailable()).isTrue();
        assertThat(find(list, "OPERATION_FAILED").emailAvailable()).isFalse(); // its switch is off
        assertThat(find(list, "LOCK_ACQUIRED").emailAvailable()).isFalse();    // never emailed
    }

    private static NotificationPreferenceDTO find(List<NotificationPreferenceDTO> list, String type) {
        return list.stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow();
    }
}
