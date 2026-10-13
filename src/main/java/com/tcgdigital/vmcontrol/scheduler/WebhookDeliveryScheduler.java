package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.webhook.WebhookDeliveryService;
import com.tcgdigital.vmcontrol.service.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends due webhook deliveries every 30 seconds (E34-T06); skipped while webhooks are off. */
@Component
public class WebhookDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);
    static final String LOCK_NAME = "webhook_delivery";

    private final WebhookDeliveryService deliveryService;
    private final WebhookService webhookService;
    private final ScheduledJobLockService lockService;

    public WebhookDeliveryScheduler(WebhookDeliveryService deliveryService, WebhookService webhookService,
                                    ScheduledJobLockService lockService) {
        this.deliveryService = deliveryService;
        this.webhookService = webhookService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${webhooks.cron:*/30 * * * * *}")
    public void scheduledRun() {
        if (!webhookService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                deliveryService.processDue();
            } catch (Exception e) {
                log.error("Error during the webhook delivery pass: {}", e.getMessage(), e);
            }
        });
    }
}
