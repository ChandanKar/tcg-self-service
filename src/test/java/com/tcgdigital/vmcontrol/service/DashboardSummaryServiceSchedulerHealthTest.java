package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each job is judged against its own schedule (E12-T05): a daily job 20 hours old is healthy, a
 * 5-minute job 20 minutes old is stale; switched-off, running and never-run jobs say so.
 */
class DashboardSummaryServiceSchedulerHealthTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-11T12:00:00Z"));

    private static Timestamp ago(Duration d) {
        return Timestamp.from(NOW.toInstant().minus(d));
    }

    private static String status(boolean enabled, Duration age, Duration interval) {
        return DashboardSummaryService.schedulerStatus(enabled, age == null ? null : ago(age), null, interval.toSeconds(), NOW);
    }

    @Test
    void aDailyJobRunTwentyHoursAgoIsHealthy() {
        assertThat(status(true, Duration.ofHours(20), Duration.ofDays(1))).isEqualTo("HEALTHY");
        assertThat(status(true, Duration.ofHours(40), Duration.ofDays(1))).isEqualTo("LATE");
        assertThat(status(true, Duration.ofDays(4), Duration.ofDays(1))).isEqualTo("STALE");
    }

    @Test
    void aFiveMinuteJobIsJudgedByItsOwnInterval() {
        assertThat(status(true, Duration.ofMinutes(6), Duration.ofMinutes(5))).isEqualTo("HEALTHY");
        assertThat(status(true, Duration.ofMinutes(12), Duration.ofMinutes(5))).isEqualTo("LATE");
        assertThat(status(true, Duration.ofMinutes(20), Duration.ofMinutes(5))).isEqualTo("STALE");
    }

    @Test
    void disabledRunningAndNeverRunJobsSaySo() {
        assertThat(status(false, Duration.ofMinutes(1), Duration.ofMinutes(5))).isEqualTo("DISABLED");
        assertThat(status(true, null, Duration.ofMinutes(5))).isEqualTo("NEVER");
        assertThat(DashboardSummaryService.schedulerStatus(true, ago(Duration.ofMinutes(1)),
                Timestamp.from(NOW.toInstant().plusSeconds(60)), 300, NOW)).isEqualTo("RUNNING");
    }

    @Test
    void theCatalogListsEveryJobWithItsScheduleAndSwitch() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("cost.tagging.enabled", "false")
                .withProperty("cloudwatch.metric.schedule.interval", "5");
        var jobs = new SchedulerJobCatalog(env).jobs();

        assertThat(jobs).hasSize(17);
        var archive = jobs.stream().filter(j -> j.jobName().equals("vm_metrics_archive")).findFirst().orElseThrow();
        assertThat(archive.expectedInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(archive.label()).isEqualTo("Metrics archive (daily)");
        assertThat(jobs.stream().filter(j -> j.jobName().equals("vm_metrics_sync")).findFirst().orElseThrow()
                .expectedInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(jobs.stream().filter(j -> j.jobName().equals("weekly_notification_reports")).findFirst().orElseThrow()
                .expectedInterval()).isEqualTo(Duration.ofDays(7));
        assertThat(jobs.stream().filter(j -> j.jobName().equals("tag_reconciliation")).findFirst().orElseThrow()
                .enabled()).isFalse();
    }

    @Test
    void schedulingSwitchedOffDisablesEveryJob() {
        var jobs = new SchedulerJobCatalog(new MockEnvironment().withProperty("app.scheduling.enabled", "false")).jobs();

        assertThat(jobs).allMatch(j -> !j.enabled());
    }
}
