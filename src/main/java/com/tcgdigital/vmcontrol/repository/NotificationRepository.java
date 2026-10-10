package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Notification;
import com.tcgdigital.vmcontrol.model.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<Notification> findByUserIdAndIsReadOrderByCreatedAtDesc(String userId, boolean isRead, Pageable pageable);

    long countByUserIdAndIsRead(String userId, boolean isRead);

    boolean existsByUserIdAndTypeAndEntityTypeAndEntityId(
            String userId, NotificationType type, String entityType, String entityId);

    boolean existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
            String userId, NotificationType type, String entityType, String entityId, Timestamp since);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
    int markAllReadForUser(@Param("userId") String userId);

    /** Retention (E11-T07): delete up to {@code limit} of the oldest rows before {@code cutoff}. */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM notification WHERE created_at < :cutoff AND is_read = TRUE ORDER BY created_at LIMIT :limit")
    int deleteReadOlderThan(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);

    /** Retention (E11-T07): delete up to {@code limit} of the oldest rows before {@code cutoff}. */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM notification WHERE created_at < :cutoff ORDER BY created_at LIMIT :limit")
    int deleteOlderThan(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);
}
