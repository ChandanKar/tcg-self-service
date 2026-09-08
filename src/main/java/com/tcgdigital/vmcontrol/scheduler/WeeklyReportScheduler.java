package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.WeeklyReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly digest reports — Cost, Idle Waste, Rightsizing — Monday 9:00 AM IST by default. Each
 * report is wrapped in its own try/catch so one report's failure doesn't block the other two;
 * all three still run under a single lock acquisition since they're cheap DB reads plus one
 * Excel build each, not worth three separate locks.
 */
@Component
@ConditionalOnProperty(name = "notification.weekly-reports.enabled", havingValue = "true", matchIfMissing = false)
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private static final String LOCK_NAME = "weekly_notification_reports";

    private final WeeklyReportService weeklyReportService;
    private final ScheduledJobLockService lockService;

    @Value("${notification.weekly-reports.enabled:false}")
    private boolean enabled;

    public WeeklyReportScheduler(WeeklyReportService weeklyReportService, ScheduledJobLockService lockService) {
        this.weeklyReportService = weeklyReportService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${notification.weekly-reports.cron:0 0 9 * * MON}", zone = "Asia/Kolkata")
    public void scheduledWeeklyReports() {
        if (!enabled || !lockService.tryAcquire(LOCK_NAME)) {
            return;
        }
        try {
            runReport("weekly cost report", weeklyReportService::sendWeeklyCostReport);
            runReport("weekly idle waste report", weeklyReportService::sendWeeklyIdleWasteReport);
            runReport("weekly rightsizing report", weeklyReportService::sendWeeklyRightsizingReport);
        } finally {
            lockService.release(LOCK_NAME);
        }
    }

    private void runReport(String reportName, Runnable report) {
        try {
            report.run();
        } catch (Exception e) {
            log.error("Error sending {}: {}", reportName, e.getMessage(), e);
        }
    }
}
