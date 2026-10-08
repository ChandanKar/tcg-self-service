package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CostExplorerBillingService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Ingests yesterday's real AWS cost once daily — billing data has ~24h latency in Cost Explorer,
 * so intra-day polling would just waste money on the ~$0.01/call charge for no benefit. Runs
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
                        costExplorerBillingService.ingestDailyActualCosts(LocalDate.now().minusDays(1));
                log.info("Scheduled actual cost ingestion complete — {} updated, {} skipped (no environment), " +
                                "{} skipped (no snapshot row)",
                        result.updated(), result.skippedNoEnvironment(), result.skippedNoSnapshotRow());
            } catch (Exception e) {
                log.error("Error during scheduled actual cost ingestion: {}", e.getMessage(), e);
            }
        });
    }
}
