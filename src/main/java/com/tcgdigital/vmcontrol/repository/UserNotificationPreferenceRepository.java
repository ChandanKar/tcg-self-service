package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.UserNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, String> {

    List<UserNotificationPreference> findByUserId(String userId);

    /** One query for a whole broadcast (E29-T05). */
    List<UserNotificationPreference> findByUserIdInAndNotificationType(Collection<String> userIds, NotificationType type);
}
