package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ComputeOptimizerService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily capture of AWS Compute Optimizer's EC2 recommendations (E30-T04), so the rightsizing page
 * reads stored rows instead of calling AWS. Read-only and free; skipped without credentials.
 */
@Component
@ConditionalOnProperty(name = "cost.optimizer.enabled", havingValue = "true", matchIfMissing = true)
public class ComputeOptimizerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ComputeOptimizerScheduler.class);
    static final String LOCK_NAME = "compute_optimizer_capture";

    private final ComputeOptimizerService computeOptimizerService;
    private final ScheduledJobLockService lockService;

    @Value("${cost.optimizer.enabled:true}")
    private boolean enabled = true;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean live(String key, boolean current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    public ComputeOptimizerScheduler(ComputeOptimizerService computeOptimizerService, ScheduledJobLockService lockService) {
        this.computeOptimizerService = computeOptimizerService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.optimizer.cron:0 45 5 * * *}")
    public void scheduledCapture() {
        if (!live("cost.optimizer.enabled", enabled)) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                computeOptimizerService.captureRecommendations();
            } catch (Exception e) {
                log.error("Error during scheduled Compute Optimizer capture: {}", e.getMessage(), e);
            }
        });
    }
}
