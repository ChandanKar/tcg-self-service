package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.BudgetEvaluationService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Daily budget evaluation (E20-T02), after the 05:00 UTC actuals import, under the cluster job
 * lock. Writes only application state and in-app notifications, so it is on by default
 * (budgets.evaluation.enabled); budget emails stay off unless enabled.
 */
@Component
@ConditionalOnProperty(name = "budgets.evaluation.enabled", havingValue = "true", matchIfMissing = true)
public class BudgetEvaluationScheduler {

    static final String JOB_NAME = "budget_evaluation";

    private final BudgetEvaluationService evaluationService;
    private final ScheduledJobLockService jobLockService;

    public BudgetEvaluationScheduler(BudgetEvaluationService evaluationService, ScheduledJobLockService jobLockService) {
        this.evaluationService = evaluationService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${budgets.evaluation.cron:0 30 5 * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(10), evaluationService::evaluateAll);
    }
}
