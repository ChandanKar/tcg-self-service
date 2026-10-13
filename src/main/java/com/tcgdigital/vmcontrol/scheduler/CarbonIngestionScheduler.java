package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.carbon.CarbonIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ingests the AWS carbon emissions Data Export daily (E37-T01), 06:00 UTC by default: only new or
 * changed files are read. Skipped while carbon.enabled is off.
 */
@Component
public class CarbonIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CarbonIngestionScheduler.class);
    static final String LOCK_NAME = "carbon_ingestion";

    private final CarbonIngestionService ingestionService;
    private final ScheduledJobLockService lockService;

    public CarbonIngestionScheduler(CarbonIngestionService ingestionService, ScheduledJobLockService lockService) {
        this.ingestionService = ingestionService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${carbon.cron:0 0 6 * * *}", zone = "UTC")
    public void scheduledIngest() {
        if (!ingestionService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                CarbonIngestionService.Result r = ingestionService.ingest();
                log.info("Carbon ingestion: {} ({} files, {} ingested, {} unchanged, {} failed, months {})",
                        r.status(), r.files(), r.ingested(), r.skipped(), r.failed(), r.months());
            } catch (Exception e) {
                log.error("Error during carbon ingestion: {}", e.getMessage(), e);
            }
        });
    }
}
