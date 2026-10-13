package com.tcgdigital.vmcontrol.service.webhook;

import com.tcgdigital.vmcontrol.service.events.PlatformEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues webhook deliveries for platform events (E34-T07) only after the publishing transaction
 * commits, so a rolled-back action sends nothing; an event published outside a transaction is
 * queued at once. No HTTP call happens here: the delivery job sends.
 */
@Component
public class WebhookEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookService webhookService;
    private final WebhookDeliveryService deliveryService;

    public WebhookEventListener(WebhookService webhookService, WebhookDeliveryService deliveryService) {
        this.webhookService = webhookService;
        this.deliveryService = deliveryService;
    }

    /** Its own transaction: after commit the publisher's transaction can no longer write anything. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onEvent(PlatformEvent event) {
        if (!webhookService.isEnabled()) {
            return;
        }
        try {
            int queued = deliveryService.enqueue(event);
            if (queued > 0) {
                log.debug("Queued {} webhook delivery(ies) for {} {}", queued, event.type().value(), event.id());
            }
        } catch (Exception e) {
            log.warn("Could not queue webhooks for {} {}: {}", event.type().value(), event.id(), e.getMessage());
        }
    }
}
