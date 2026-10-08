package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.AutomationRuleService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for evaluating calendar-schedule automation rules.
 * Default: every 60 seconds — matched to "HH:mm" granularity, so sub-minute tick offset
 * doesn't matter as long as each minute is observed at least once.
 */
/**
 * Ticks every automation.rules.interval (1 min) under the cluster job lock. A schedule time fires
 * on the first tick at or after it, up to automation.rules.catch-up-minutes late; a locked or
 * busy environment is retried on later ticks within that window (see AutomationRuleService).
 */
@Component
@ConditionalOnProperty(name = "automation.rules.enabled", havingValue = "true", matchIfMissing = true)
public class AutomationRuleScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleScheduler.class);
    private static final String LOCK_NAME = "automation_rules";

    private final AutomationRuleService automationRuleService;
    private final ScheduledJobLockService scheduledJobLockService;

    @Value("${automation.rules.enabled:true}")
    private boolean enabled;

    public AutomationRuleScheduler(AutomationRuleService automationRuleService,
                                    ScheduledJobLockService scheduledJobLockService) {
        this.automationRuleService = automationRuleService;
        this.scheduledJobLockService = scheduledJobLockService;
    }

    @Scheduled(fixedRateString = "${automation.rules.interval:60000}",
               initialDelayString = "${automation.rules.initial-delay:60000}")
    public void scheduledEvaluate() {
        if (!enabled) {
            return;
        }
        scheduledJobLockService.runLocked(LOCK_NAME, () -> {
            try {
                automationRuleService.evaluateSchedules();
            } catch (Exception e) {
                log.error("Error during scheduled automation rule evaluation: {}", e.getMessage(), e);
            }
        });
    }
}
