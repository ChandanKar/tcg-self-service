package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.NotificationPreferenceDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.UserNotificationPreference;
import com.tcgdigital.vmcontrol.repository.UserNotificationPreferenceRepository;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Which notifications reach each user, by type and channel (E29-T05). Everything is on until the
 * user turns it off. Some bell notifications cannot be turned off (access ending, a broken lock,
 * a stop notice, a failed operation, an approval to give, a lease ending); email can be turned off
 * for every type. Broadcast filtering loads the preferences of all recipients in one query.
 */
@Service
public class NotificationPreferenceService {

    /** Bell notifications a user must always get. */
    public static final Set<NotificationType> MANDATORY_IN_APP = EnumSet.of(
            NotificationType.ACCESS_REVOKED, NotificationType.ACCESS_EXPIRED, NotificationType.LOCK_BROKEN,
            NotificationType.ENVIRONMENT_STOP_NOTICE, NotificationType.OPERATION_FAILED, NotificationType.APPROVAL_REQUESTED,
            NotificationType.LEASE_EXPIRING, NotificationType.LEASE_EXPIRED);

    /** The platform email switch of each type that is ever emailed (others have no email). */
    static final Map<NotificationType, String> EMAIL_SETTING = new EnumMap<>(Map.ofEntries(
            Map.entry(NotificationType.ACCESS_REQUESTED, "notification.email.access-requested.enabled"),
            Map.entry(NotificationType.ACCESS_EXPIRING, "notification.email.access-expiring.enabled"),
            Map.entry(NotificationType.ACCESS_EXPIRED, "notification.email.access-expired.enabled"),
            Map.entry(NotificationType.ACCESS_GRANTED, "notification.email.access-granted.enabled"),
            Map.entry(NotificationType.ACCESS_LEVEL_CHANGED, "notification.email.access-granted.enabled"),
            Map.entry(NotificationType.ACCESS_REQUEST_APPROVED, "notification.email.access-request-approved.enabled"),
            Map.entry(NotificationType.ACCESS_REQUEST_DENIED, "notification.email.access-request-denied.enabled"),
            Map.entry(NotificationType.ACCESS_REVOKED, "notification.email.access-revoked.enabled"),
            Map.entry(NotificationType.OPERATION_FAILED, "notification.email.operation-failed.enabled"),
            Map.entry(NotificationType.LOCK_BROKEN, "notification.email.lock-broken.enabled"),
            Map.entry(NotificationType.LEASE_EXPIRING, "notification.email.lease-expiring.enabled"),
            Map.entry(NotificationType.BUDGET_THRESHOLD, "notification.email.budget-alert.enabled"),
            Map.entry(NotificationType.BUDGET_FORECAST, "notification.email.budget-alert.enabled"),
            Map.entry(NotificationType.COST_ANOMALY, "notification.email.cost-anomaly.enabled"),
            Map.entry(NotificationType.WEEKLY_COST_REPORT, "notification.email.weekly-cost-report.enabled"),
            Map.entry(NotificationType.WEEKLY_IDLE_WASTE_REPORT, "notification.email.weekly-idle-waste-report.enabled"),
            Map.entry(NotificationType.WEEKLY_RIGHTSIZING_REPORT, "notification.email.weekly-rightsizing-report.enabled"),
            Map.entry(NotificationType.ENVIRONMENT_STOP_NOTICE, "notification.email.enabled")));

    private final UserNotificationPreferenceRepository repository;

    @Autowired(required = false)
    private SettingsService settings;

    public NotificationPreferenceService(UserNotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    // ---- delivery checks ----

    public boolean wantsInApp(String userId, NotificationType type) {
        if (userId == null || MANDATORY_IN_APP.contains(type)) {
            return true;
        }
        return repository.findByUserIdInAndNotificationType(List.of(userId), type).stream()
                .findFirst().map(UserNotificationPreference::isInAppEnabled).orElse(true);
    }

    /** The user ids that want this type in the bell, from one query. */
    public Set<String> inAppRecipients(NotificationType type, Collection<String> userIds) {
        Set<String> wanted = new LinkedHashSet<>(userIds);
        if (MANDATORY_IN_APP.contains(type) || userIds.isEmpty()) {
            return wanted;
        }
        repository.findByUserIdInAndNotificationType(userIds, type).stream()
                .filter(p -> !p.isInAppEnabled())
                .forEach(p -> wanted.remove(p.getUserId()));
        return wanted;
    }

    /** The users that want this type by email, from one query. */
    public List<User> emailRecipients(NotificationType type, List<User> users) {
        if (users.isEmpty()) {
            return users;
        }
        Set<String> off = repository.findByUserIdInAndNotificationType(
                        users.stream().filter(Objects::nonNull).map(User::getUserId).toList(), type).stream()
                .filter(p -> !p.isEmailEnabled())
                .map(UserNotificationPreference::getUserId)
                .collect(Collectors.toSet());
        return users.stream().filter(u -> u != null && !off.contains(u.getUserId())).toList();
    }

    public boolean wantsEmail(String userId, NotificationType type) {
        return repository.findByUserIdInAndNotificationType(List.of(userId), type).stream()
                .findFirst().map(UserNotificationPreference::isEmailEnabled).orElse(true);
    }

    // ---- the user's own page ----

    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> list(String userId) {
        Map<NotificationType, UserNotificationPreference> stored = new EnumMap<>(NotificationType.class);
        repository.findByUserId(userId).forEach(p -> stored.put(p.getNotificationType(), p));
        List<NotificationPreferenceDTO> result = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            UserNotificationPreference p = stored.get(type);
            boolean locked = MANDATORY_IN_APP.contains(type);
            result.add(new NotificationPreferenceDTO(type.name(), label(type), category(type),
                    locked || p == null || p.isInAppEnabled(), p == null || p.isEmailEnabled(), locked, emailAvailable(type)));
        }
        return result;
    }

    @Transactional
    public List<NotificationPreferenceDTO> update(String userId, List<NotificationPreferenceDTO.Update> changes) {
        Map<NotificationType, UserNotificationPreference> stored = new EnumMap<>(NotificationType.class);
        repository.findByUserId(userId).forEach(p -> stored.put(p.getNotificationType(), p));
        for (NotificationPreferenceDTO.Update change : changes == null ? List.<NotificationPreferenceDTO.Update>of() : changes) {
            NotificationType type;
            try {
                type = NotificationType.valueOf(change.type());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ValidationException("Unknown notification type: " + change.type());
            }
            UserNotificationPreference p = stored.computeIfAbsent(type, t -> new UserNotificationPreference(userId, t));
            if (change.inApp() != null) {
                // A required bell notification stays on, whatever is sent.
                p.setInAppEnabled(MANDATORY_IN_APP.contains(type) || change.inApp());
            }
            if (change.email() != null) {
                p.setEmailEnabled(change.email());
            }
            repository.save(p);
        }
        return list(userId);
    }

    private boolean emailAvailable(NotificationType type) {
        String key = EMAIL_SETTING.get(type);
        if (key == null) {
            return false;
        }
        if (settings == null) {
            return true;
        }
        return settings.getBoolean("notification.email.enabled", false) && settings.getBoolean(key, false);
    }

    static String label(NotificationType type) {
        String words = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static String category(NotificationType type) {
        String name = type.name();
        if (name.startsWith("ACCESS_")) return "Access";
        if (name.startsWith("LOCK_")) return "Locks";
        if (name.startsWith("OPERATION_") || name.startsWith("SPOT_") || name.startsWith("ENVIRONMENT_DESTROY")
                || name.equals("ENVIRONMENT_STOP_NOTICE")
                || name.equals("IDLE_AUTO_STOPPED")) return "Operations";
        if (name.startsWith("LEASE_")) return "Leases";
        if (name.startsWith("APPROVAL_") || name.startsWith("QUOTA_") || name.startsWith("PROVISIONING_")) return "Approvals and quotas";
        if (name.startsWith("BUDGET_") || name.startsWith("COST_") || name.startsWith("WEEKLY_")) return "Cost";
        return "Environments";
    }
}
