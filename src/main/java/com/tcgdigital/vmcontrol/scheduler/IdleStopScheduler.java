package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.idle.IdleStopService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Evaluates idle auto-stop rules every automation.idle-stop.interval (5 min) under the cluster
 * job lock (E16-T03). Absent unless automation.idle-stop.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "automation.idle-stop.enabled", havingValue = "true", matchIfMissing = false)
public class IdleStopScheduler {

    static final String LOCK_NAME = "idle_auto_stop";

    private final IdleStopService idleStopService;
    private final ScheduledJobLockService scheduledJobLockService;

    public IdleStopScheduler(IdleStopService idleStopService, ScheduledJobLockService scheduledJobLockService) {
        this.idleStopService = idleStopService;
        this.scheduledJobLockService = scheduledJobLockService;
    }

    @Scheduled(fixedRateString = "${automation.idle-stop.interval:300000}",
               initialDelayString = "${automation.idle-stop.initial-delay:120000}")
    public void evaluate() {
        scheduledJobLockService.runLocked(LOCK_NAME, Duration.ofMinutes(10), idleStopService::evaluateAll);
    }
}
