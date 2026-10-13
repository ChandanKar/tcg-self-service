package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    /** Deliveries due now, oldest first. */
    @Query("SELECT d FROM WebhookDelivery d WHERE d.status = com.tcgdigital.vmcontrol.model.WebhookDelivery.Status.PENDING " +
           "AND d.nextAttemptAt <= :now ORDER BY d.nextAttemptAt")
    List<WebhookDelivery> findDue(@Param("now") Timestamp now, Pageable limit);

    Page<WebhookDelivery> findByWebhookIdOrderByCreatedAtDesc(String webhookId, Pageable pageable);

    /** The latest delivery of each subscription, for the list's last-delivery column. */
    WebhookDelivery findFirstByWebhookIdOrderByCreatedAtDesc(String webhookId);
}
