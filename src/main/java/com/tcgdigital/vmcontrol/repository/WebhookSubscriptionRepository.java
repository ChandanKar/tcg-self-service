package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, String> {

    /** Active subscriptions that may receive an event of this environment: its own and the global ones. */
    @Query("SELECT w FROM WebhookSubscription w WHERE w.active = true AND (w.environmentId IS NULL OR w.environmentId = :environmentId)")
    List<WebhookSubscription> findActiveFor(@Param("environmentId") String environmentId);

    List<WebhookSubscription> findAllByOrderByCreatedAtDesc();

    List<WebhookSubscription> findByEnvironmentIdInOrderByCreatedAtDesc(List<String> environmentIds);

    List<WebhookSubscription> findByEnvironmentIdOrderByCreatedAtDesc(String environmentId);
}
