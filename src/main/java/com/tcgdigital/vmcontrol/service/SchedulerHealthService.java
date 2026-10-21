package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
import com.tcgdigital.vmcontrol.dto.SchedulerJobDTO;
import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every scheduled job and how it is doing against its own schedule (E12-T05). The dashboard
 * summary and Home's attention summary read {@link #jobs(boolean)}; System health (U14-T02) reads
 * {@link #forSystemHealth()}: each job's name, how often it runs in words ("Every 5 min", "Daily
 * 03:00"), its last run and On time / late / Switched off, problems first.
 */
@Service
public class SchedulerHealthService {

    private final SchedulerJobCatalog jobCatalog;
    private final ScheduledJobLockRepository lockRepository;

    public SchedulerHealthService(SchedulerJobCatalog jobCatalog, ScheduledJobLockRepository lockRepository) {
        this.jobCatalog = jobCatalog;
        this.lockRepository = lockRepository;
    }

    /**
     * Every job from the catalog: DISABLED when switched off, RUNNING while its lock is held, NEVER
     * without a lock row, else HEALTHY / LATE / STALE against 1.5x and 3x its own interval. Lock
     * owners (host names) are shown to admins only.
     */
    public List<DashboardSummaryDTO.SchedulerHealthDTO> jobs(boolean admin) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, ScheduledJobLock> locks = locksByName();
        return jobCatalog.jobs().stream().map(job -> {
            ScheduledJobLock lock = locks.get(job.jobName());
            long intervalSeconds = job.expectedInterval().toSeconds();
            Timestamp lastRunAt = lock == null ? null : lock.getAcquiredAt();
            Timestamp lockedUntil = lock == null ? null : lock.getLockedUntil();
            return new DashboardSummaryDTO.SchedulerHealthDTO(
                    job.label(),
                    schedulerStatus(job.enabled(), lastRunAt, lockedUntil, intervalSeconds, now),
                    lastRunAt,
                    lockedUntil,
                    lastRunAt == null ? null : Math.max(0, (now.getTime() - lastRunAt.getTime()) / 1000),
                    admin && lock != null ? lock.getLockedBy() : null,
                    intervalSeconds);
        }).toList();
    }

    /** System health's job list: late first (never ran, then the latest), then on time, switched off last. */
    public List<SchedulerJobDTO> forSystemHealth() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, ScheduledJobLock> locks = locksByName();
        List<SchedulerJobDTO> out = new ArrayList<>();
        int position = 0;
        for (SchedulerJobCatalog.Job job : jobCatalog.jobs()) {
            ScheduledJobLock lock = locks.get(job.jobName());
            long interval = job.expectedInterval().toSeconds();
            Timestamp lastRunAt = lock == null ? null : lock.getAcquiredAt();
            String raw = schedulerStatus(job.enabled(), lastRunAt, lock == null ? null : lock.getLockedUntil(), interval, now);
            String status = switch (raw) {
                case "DISABLED" -> SchedulerJobDTO.OFF;
                case "HEALTHY", "RUNNING" -> SchedulerJobDTO.ON_TIME;
                default -> SchedulerJobDTO.LATE;
            };
            Long lateBy = SchedulerJobDTO.LATE.equals(status) && lastRunAt != null
                    ? Math.max(0, (now.getTime() - lastRunAt.getTime()) / 1000 - interval) : null;
            out.add(new SchedulerJobDTO(job.jobName(), name(job.label()), cadence(job.expectedInterval(), job.cron()),
                    status, lastRunAt, lateBy, "RUNNING".equals(raw), position++));
        }
        out.sort(Comparator.comparingInt(SchedulerHealthService::rank)
                .thenComparing((SchedulerJobDTO j) -> j.lateBySeconds() == null ? Long.MAX_VALUE : j.lateBySeconds(), Comparator.reverseOrder())
                .thenComparingInt(SchedulerJobDTO::position));
        return out;
    }

    private static int rank(SchedulerJobDTO job) {
        return switch (job.status()) {
            case SchedulerJobDTO.LATE -> 0;
            case SchedulerJobDTO.ON_TIME -> 1;
            default -> 2;
        };
    }

    private Map<String, ScheduledJobLock> locksByName() {
        return lockRepository.findAll().stream()
                .collect(Collectors.toMap(ScheduledJobLock::getLockName, Function.identity(), (left, right) -> left));
    }

    static String schedulerStatus(boolean enabled, Timestamp lastRunAt, Timestamp lockedUntil,
                                  long intervalSeconds, Timestamp now) {
        if (!enabled) return "DISABLED";
        if (lockedUntil != null && lockedUntil.after(now)) return "RUNNING";
        if (lastRunAt == null) return "NEVER";
        long age = Math.max(0, (now.getTime() - lastRunAt.getTime()) / 1000);
        if (age <= intervalSeconds * 3 / 2) return "HEALTHY";
        if (age <= intervalSeconds * 3) return "LATE";
        return "STALE";
    }

    /** "Cost snapshot (daily)" is shown as "Cost snapshot": the Runs column says how often. */
    static String name(String label) {
        return label == null ? null : label.replaceFirst("\\s*\\([^)]*\\)\\s*$", "");
    }

    /**
     * How often a job runs, in words: "Every 30 s", "Every minute", "Every 5 min", "Hourly", "Every
     * 12 hours", "Daily 03:00", "Weekly, Mon 09:00", "Monthly, day 5 06:00"; any other cron
     * expression as written.
     */
    static String cadence(Duration interval, String cron) {
        if (cron == null) {
            return every(interval);
        }
        String[] f = cron.trim().split("\\s+");
        if (f.length != 6) {
            return cron;
        }
        String sec = f[0], min = f[1], hour = f[2], dom = f[3], mon = f[4], dow = f[5];
        boolean everyDay = "*".equals(dom) && "*".equals(mon) && ("*".equals(dow) || "?".equals(dow));
        if (sec.startsWith("*/") && "*".equals(min) && "*".equals(hour) && everyDay && number(sec.substring(2))) {
            return every(Duration.ofSeconds(Long.parseLong(sec.substring(2))));
        }
        if (!number(sec)) {
            return cron;
        }
        if (min.startsWith("*/") && "*".equals(hour) && everyDay && number(min.substring(2))) {
            return every(Duration.ofMinutes(Long.parseLong(min.substring(2))));
        }
        if ("*".equals(min) && "*".equals(hour) && everyDay) {
            return "Every minute";
        }
        if (!number(min)) {
            return cron;
        }
        if ("*".equals(hour) && everyDay) {
            return "Hourly";
        }
        if (hour.startsWith("*/") && everyDay && number(hour.substring(2))) {
            return every(Duration.ofHours(Long.parseLong(hour.substring(2))));
        }
        if (!number(hour)) {
            return cron;
        }
        String at = String.format(Locale.ROOT, "%02d:%02d", Integer.parseInt(hour), Integer.parseInt(min));
        if (everyDay) {
            return "Daily " + at;
        }
        if ("*".equals(dom) && "*".equals(mon) && dow.matches("[A-Za-z]{3}")) {
            return "Weekly, " + dow.charAt(0) + dow.substring(1).toLowerCase(Locale.ROOT) + " " + at;
        }
        if (number(dom) && "*".equals(mon) && ("*".equals(dow) || "?".equals(dow))) {
            return "Monthly, day " + Integer.parseInt(dom) + " " + at;
        }
        return cron;
    }

    static String every(Duration interval) {
        long s = interval.toSeconds();
        if (s <= 0) return "Continuously";
        if (s < 60) return "Every " + s + " s";
        if (s == 60) return "Every minute";
        if (s < 3600) return s % 60 == 0 ? "Every " + s / 60 + " min" : "Every " + s + " s";
        if (s == 3600) return "Hourly";
        if (s < 86400) return s % 3600 == 0 ? "Every " + s / 3600 + " hours" : "Every " + s / 60 + " min";
        if (s == 86400) return "Daily";
        return s % 86400 == 0 ? "Every " + s / 86400 + " days" : "Every " + s / 3600 + " hours";
    }

    private static boolean number(String value) {
        return value.matches("\\d+");
    }
}
