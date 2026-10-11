package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ChatDeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Chat delivery attempts (E24-T03). */
public interface ChatDeliveryLogRepository extends JpaRepository<ChatDeliveryLog, String> {

    boolean existsByChannelIdAndDedupKeyAndSuccessTrue(String channelId, String dedupKey);

    List<ChatDeliveryLog> findByChannelIdOrderBySentAtDesc(String channelId);

    /** Retention: delete up to {@code limit} of the oldest rows before {@code cutoff}. */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM chat_delivery_log WHERE sent_at < :cutoff ORDER BY sent_at LIMIT :limit")
    int deleteOlderThan(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);
}
