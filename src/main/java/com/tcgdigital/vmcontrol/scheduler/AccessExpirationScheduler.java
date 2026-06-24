package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for processing expired environment access grants.
 * Runs daily to mark expired access grants as expired.
 */
@Component
public class AccessExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccessExpirationScheduler.class);

    private final EnvironmentAccessService accessService;

    public AccessExpirationScheduler(EnvironmentAccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * Process expired access grants.
     * Runs daily at 1:00 AM.
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void processExpiredAccess() {
        log.info("Starting access expiration check...");

        try {
            int expiringCount = accessService.processExpiringAccessWarnings();
            if (expiringCount > 0) {
                log.info("Processed {} access expiration warning candidates", expiringCount);
            }

            int expiredCount = accessService.processExpiredAccess();

            if (expiredCount > 0) {
                log.info("Processed {} expired access grants", expiredCount);
            } else {
                log.debug("No expired access grants found");
            }
        } catch (Exception e) {
            log.error("Error processing expired access grants", e);
        }
    }

    /**
     * Manual trigger for access expiration processing.
     * Can be called via a REST endpoint for testing or manual execution.
     */
    public int triggerManualExpiration() {
        log.info("Manual access expiration triggered");
        accessService.processExpiringAccessWarnings();
        return accessService.processExpiredAccess();
    }
}

