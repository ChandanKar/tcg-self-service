package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.provisioning.ProvisioningExecutor;
import com.tcgdigital.vmcontrol.service.provisioning.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Builds approved environment requests every minute (E32-T04); skipped while provisioning is off. */
@Component
public class ProvisioningScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningScheduler.class);
    static final String LOCK_NAME = "provisioning";

    private final ProvisioningExecutor executor;
    private final com.tcgdigital.vmcontrol.service.provisioning.ProvisioningDestroyService destroyService;
    private final TemplateService templateService;
    private final ScheduledJobLockService lockService;

    public ProvisioningScheduler(ProvisioningExecutor executor,
                                 com.tcgdigital.vmcontrol.service.provisioning.ProvisioningDestroyService destroyService,
                                 TemplateService templateService, ScheduledJobLockService lockService) {
        this.executor = executor;
        this.destroyService = destroyService;
        this.templateService = templateService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${provisioning.cron:0 * * * * *}")
    public void scheduledRun() {
        if (!templateService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                executor.run();
            } catch (Exception e) {
                log.error("Error during the provisioning run: {}", e.getMessage(), e);
            }
            try {
                // E32-T05: warn before the destroy time, destroy what is due (only with provisioning.destroy.enabled), follow destroys
                destroyService.warn();
                destroyService.destroyDue();
                destroyService.pollDestroying();
            } catch (Exception e) {
                log.error("Error during the provisioning destroy pass: {}", e.getMessage(), e);
            }
        });
    }
}
