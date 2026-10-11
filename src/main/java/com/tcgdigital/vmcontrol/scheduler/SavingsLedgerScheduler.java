package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.SavingsLedgerService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Daily savings ledger (E17-T03), under the cluster job lock. Writes only to the application
 * database, so it is on by default (savings.ledger.enabled).
 */
@Component
@ConditionalOnProperty(name = "savings.ledger.enabled", havingValue = "true", matchIfMissing = true)
public class SavingsLedgerScheduler {

    static final String JOB_NAME = "savings_ledger";

    private final SavingsLedgerService ledgerService;
    private final com.tcgdigital.vmcontrol.service.PotentialSavingsService potentialSavingsService;
    private final ScheduledJobLockService jobLockService;

    public SavingsLedgerScheduler(SavingsLedgerService ledgerService,
                                  com.tcgdigital.vmcontrol.service.PotentialSavingsService potentialSavingsService,
                                  ScheduledJobLockService jobLockService) {
        this.ledgerService = ledgerService;
        this.potentialSavingsService = potentialSavingsService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${savings.ledger.cron:0 30 3 * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(30), () -> {
            ledgerService.captureTrailing();
            potentialSavingsService.captureSnapshot(); // today's Cost Efficiency % (E17-T05)
        });
    }
}
