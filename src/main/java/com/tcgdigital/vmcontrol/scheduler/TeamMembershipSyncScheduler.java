package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.TeamMembershipSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Hourly Entra group membership sync of teams (E23-T02), under the cluster job lock. Off unless
 * teams.entra-sync.enabled; it also needs graph.directory.enabled (else it does nothing).
 */
@Component
@ConditionalOnProperty(name = "teams.entra-sync.enabled", havingValue = "true")
public class TeamMembershipSyncScheduler {

    static final String JOB_NAME = "team_membership_sync";

    private final TeamMembershipSyncService syncService;
    private final ScheduledJobLockService jobLockService;

    public TeamMembershipSyncScheduler(TeamMembershipSyncService syncService, ScheduledJobLockService jobLockService) {
        this.syncService = syncService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${teams.entra-sync.cron:0 15 * * * *}", zone = "UTC")
    public void run() {
        if (!syncService.isAvailable()) {
            return;
        }
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(15), syncService::syncAll);
    }
}
