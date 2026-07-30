package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.TagReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly safety-net sweep that re-applies cost-allocation tags to every active VM — catches
 * tag drift (a manual console edit) and VMs registered before this feature existed. Defaults to
 * disabled: writing to real AWS resource tags is a bigger blast radius than the read-only
 * reporting the rest of Cost Management does, so this requires explicit opt-in.
 */
@Component
@ConditionalOnProperty(name = "cost.tagging.enabled", havingValue = "true", matchIfMissing = false)
public class TagReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TagReconciliationScheduler.class);
    private static final String LOCK_NAME = "tag_reconciliation";

    private final TagReconciliationService tagReconciliationService;
    private final ScheduledJobLockService lockService;

    @Value("${cost.tagging.enabled:false}")
    private boolean enabled;

    public TagReconciliationScheduler(TagReconciliationService tagReconciliationService,
                                       ScheduledJobLockService lockService) {
        this.tagReconciliationService = tagReconciliationService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.tagging.cron:0 30 2 * * *}")
    public void scheduledTagReconciliation() {
        try {
            if (!enabled || !lockService.tryAcquire(LOCK_NAME)) return;
            TagReconciliationService.Result result = tagReconciliationService.reconcileAll();
            log.info("Scheduled tag reconciliation complete — {} tagged, {} failed, {} total",
                    result.tagged(), result.failed(), result.total());
        } catch (Exception e) {
            log.error("Error during scheduled tag reconciliation: {}", e.getMessage(), e);
        } finally {
            lockService.release(LOCK_NAME);
        }
    }
}
