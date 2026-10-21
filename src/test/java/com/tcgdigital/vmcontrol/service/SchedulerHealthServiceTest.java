package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each job is judged against its own schedule (E12-T05): a daily job 20 hours old is healthy, a
 * 5-minute job 20 minutes old is stale; switched-off, running and never-run jobs say so. System
 * health (U14-T02) says how often each runs in words and lists problems first.
 */
class SchedulerHealthServiceTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-11T12:00:00Z"));

    private static Timestamp ago(Duration d) {
        return Timestamp.from(NOW.toInstant().minus(d));
    }

    private static String status(boolean enabled, Duration age, Duration interval) {
        return SchedulerHealthService.schedulerStatus(enabled, age == null ? null : ago(age), null, interval.toSeconds(), NOW);
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
        assertThat(SchedulerHealthService.schedulerStatus(true, ago(Duration.ofMinutes(1)),
                Timestamp.from(NOW.toInstant().plusSeconds(60)), 300, NOW)).isEqualTo("RUNNING");
    }

    @Test
    void theCatalogListsEveryJobWithItsScheduleAndSwitch() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("cost.tagging.enabled", "false")
                .withProperty("cloudwatch.metric.schedule.interval", "5");
        var jobs = new SchedulerJobCatalog(env).jobs();

        assertThat(jobs).hasSize(35);
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

    @Test
    void howOftenAJobRunsInWords() {
        assertThat(SchedulerHealthService.cadence(Duration.ofMinutes(5), null)).isEqualTo("Every 5 min");
        assertThat(SchedulerHealthService.cadence(Duration.ofMinutes(1), null)).isEqualTo("Every minute");
        assertThat(SchedulerHealthService.cadence(Duration.ofHours(1), null)).isEqualTo("Hourly");
        assertThat(SchedulerHealthService.cadence(Duration.ofHours(6), null)).isEqualTo("Every 6 hours");
        assertThat(SchedulerHealthService.cadence(Duration.ofDays(1), "0 0 3 * * *")).isEqualTo("Daily 03:00");
        assertThat(SchedulerHealthService.cadence(Duration.ofDays(1), "0 30 2 * * *")).isEqualTo("Daily 02:30");
        assertThat(SchedulerHealthService.cadence(Duration.ofDays(7), "0 0 9 * * MON")).isEqualTo("Weekly, Mon 09:00");
        assertThat(SchedulerHealthService.cadence(Duration.ofDays(30), "0 0 6 5 * *")).isEqualTo("Monthly, day 5 06:00");
        assertThat(SchedulerHealthService.cadence(Duration.ofMinutes(10), "0 */10 * * * *")).isEqualTo("Every 10 min");
        assertThat(SchedulerHealthService.cadence(Duration.ofHours(1), "0 15 * * * *")).isEqualTo("Hourly");
        assertThat(SchedulerHealthService.cadence(Duration.ofHours(12), "0 0 */12 * * *")).isEqualTo("Every 12 hours");
        assertThat(SchedulerHealthService.cadence(Duration.ofMinutes(1), "0 * * * * *")).isEqualTo("Every minute");
        assertThat(SchedulerHealthService.cadence(Duration.ofSeconds(30), "*/30 * * * * *")).isEqualTo("Every 30 s");
        assertThat(SchedulerHealthService.cadence(Duration.ofDays(1), "0 0 1-5 * * *")).isEqualTo("0 0 1-5 * * *");
        assertThat(SchedulerHealthService.name("Cost snapshot (daily)")).isEqualTo("Cost snapshot");
        assertThat(SchedulerHealthService.name("State sync")).isEqualTo("State sync");
    }

    @Test
    void systemHealthListsLateJobsFirstAndSwitchedOffLast() {
        MockEnvironment env = new MockEnvironment();
        com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository locks =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository.class);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        java.util.function.BiFunction<String, Duration, com.tcgdigital.vmcontrol.model.ScheduledJobLock> lock = (name, age) -> {
            com.tcgdigital.vmcontrol.model.ScheduledJobLock l = new com.tcgdigital.vmcontrol.model.ScheduledJobLock();
            l.setLockName(name);
            l.setAcquiredAt(Timestamp.from(now.toInstant().minus(age)));
            l.setLockedUntil(Timestamp.from(now.toInstant().minus(age)));
            return l;
        };
        // State sync (every 5 min) last ran 12 minutes ago: 7 min past its interval. Inventory (hourly) 30 min ago: on time.
        org.mockito.Mockito.when(locks.findAll()).thenReturn(java.util.List.of(
                lock.apply("vm_state_sync", Duration.ofMinutes(12)), lock.apply("vm_inventory_sync", Duration.ofMinutes(30))));

        var jobs = new SchedulerHealthService(new SchedulerJobCatalog(env), locks).forSystemHealth();

        var stateSync = jobs.stream().filter(j -> j.jobName().equals("vm_state_sync")).findFirst().orElseThrow();
        assertThat(stateSync.status()).isEqualTo("LATE");
        assertThat(stateSync.lateBySeconds()).isBetween(415L, 425L);
        assertThat(stateSync.cadence()).isEqualTo("Every 5 min");
        var lateOnes = jobs.stream().takeWhile(j -> j.status().equals("LATE")).toList();
        assertThat(lateOnes).isNotEmpty();
        // Never-run jobs (no late-by) come before the state sync, which comes before the on-time jobs.
        assertThat(lateOnes.get(lateOnes.size() - 1).jobName()).isEqualTo("vm_state_sync");
        assertThat(jobs.get(lateOnes.size()).status()).isEqualTo("ON_TIME");
        assertThat(jobs.get(jobs.size() - 1).status()).isEqualTo("OFF");
        assertThat(jobs.stream().filter(j -> j.jobName().equals("vm_inventory_sync")).findFirst().orElseThrow().status()).isEqualTo("ON_TIME");
        assertThat(jobs.stream().filter(j -> j.jobName().equals("cost_snapshot")).findFirst().orElseThrow().cadence()).isEqualTo("Daily 03:00");
    }
}
