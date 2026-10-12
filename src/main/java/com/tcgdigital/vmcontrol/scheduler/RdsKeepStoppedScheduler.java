package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.RdsCloudProviderService;
import com.tcgdigital.vmcontrol.service.RdsKeepStoppedService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 15 minutes: RDS databases AWS started after seven days stopped (E33-T04); skipped while RDS is off. */
@Component
public class RdsKeepStoppedScheduler {

    private static final Logger log = LoggerFactory.getLogger(RdsKeepStoppedScheduler.class);
    static final String LOCK_NAME = "rds_keep_stopped";

    private final RdsKeepStoppedService keepStoppedService;
    private final RdsCloudProviderService rdsService;
    private final ScheduledJobLockService lockService;

    public RdsKeepStoppedScheduler(RdsKeepStoppedService keepStoppedService, RdsCloudProviderService rdsService,
                                   ScheduledJobLockService lockService) {
        this.keepStoppedService = keepStoppedService;
        this.rdsService = rdsService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${rds.keep-stopped.cron:0 */15 * * * *}")
    public void scheduledRun() {
        if (!rdsService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                int restopped = keepStoppedService.run();
                if (restopped > 0) {
                    log.info("RDS keep-stopped: {} database(s) stopped again", restopped);
                }
            } catch (Exception e) {
                log.error("Error during the RDS keep-stopped pass: {}", e.getMessage(), e);
            }
        });
    }
}
