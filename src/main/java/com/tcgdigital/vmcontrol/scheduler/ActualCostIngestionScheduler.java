package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CostExplorerBillingService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Re-ingests the last cost.actuals.trailing-days complete days of real AWS cost once daily
 * (E08-T02): Cost Explorer figures stay provisional for about 72h, so recent days are overwritten
 * with the corrected numbers. One billed request (plus one per extra page) per run; intra-day
 * polling would just waste money on the ~$0.01/call charge. Runs
 * after the 3am estimate snapshot so the actuals scheduler finds an existing snapshot row to
 * attach to. Disabled by default: requires Phase 1's cost-allocation tags to already be active
 * in Cost Explorer (24h propagation) or every day will resolve zero environments.
 */
@Component
@ConditionalOnProperty(name = "cost.actuals.enabled", havingValue = "true", matchIfMissing = false)
public class ActualCostIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActualCostIngestionScheduler.class);
    private static final String LOCK_NAME = "actual_cost_ingestion";

    private final CostExplorerBillingService costExplorerBillingService;
    private final ScheduledJobLockService lockService;

    @Value("${cost.actuals.enabled:false}")
    private boolean enabled;

    @Value("${cost.actuals.trailing-days:3}")
    private int trailingDays = 3;

    public ActualCostIngestionScheduler(CostExplorerBillingService costExplorerBillingService,
                                         ScheduledJobLockService lockService) {
        this.costExplorerBillingService = costExplorerBillingService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.actuals.cron:0 0 5 * * *}")
    public void scheduledActualCostIngestion() {
        if (!enabled) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                CostExplorerBillingService.IngestResult result =
                        costExplorerBillingService.ingestTrailingWindow(trailingDays);
                log.info("Scheduled actual cost ingestion complete — {} updated, {} skipped (no environment), " +
                                "{} skipped (no snapshot row)",
                        result.updated(), result.skippedNoEnvironment(), result.skippedNoSnapshotRow());
            } catch (Exception e) {
                log.error("Error during scheduled actual cost ingestion: {}", e.getMessage(), e);
            }
        });
    }
}
