package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CostSnapshotService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cost.snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class CostSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(CostSnapshotScheduler.class);
    private static final String LOCK_NAME = "cost_snapshot";

    private final CostSnapshotService costSnapshotService;
    private final ScheduledJobLockService lockService;

    @Value("${cost.snapshot.enabled:true}")
    private boolean enabled;

    public CostSnapshotScheduler(CostSnapshotService costSnapshotService, ScheduledJobLockService lockService) {
        this.costSnapshotService = costSnapshotService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.snapshot.cron:0 0 3 * * *}")
    public void scheduledCostSnapshot() {
        try {
            if (!enabled || !lockService.tryAcquire(LOCK_NAME)) return;
            costSnapshotService.captureDailySnapshot();
        } catch (Exception e) {
            log.error("Error during scheduled cost snapshot capture: {}", e.getMessage(), e);
        } finally {
            lockService.release(LOCK_NAME);
        }
    }
}
