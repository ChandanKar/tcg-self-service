package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;

/**
 * One scheduled job on System health (U14-T02): "Cost snapshot", "Daily 03:00", its last run and
 * ON_TIME, LATE (lateBySeconds past its interval; null when it never ran) or OFF. running is true
 * while it holds its lock; position is its place in the catalog (the "All" order).
 */
public record SchedulerJobDTO(String jobName, String name, String cadence, String status, Timestamp lastRunAt,
                              Long lateBySeconds, boolean running, int position, Timestamp nextRunAt) {

    /**
     * nextRunAt (U23-T13): when a job with a time of day ("Daily 02:30", in its scheduler's zone) next
     * runs, so the page can show that time in the viewer's zone, as it shows the last run; null otherwise.
     */
    public SchedulerJobDTO(String jobName, String name, String cadence, String status, Timestamp lastRunAt,
                           Long lateBySeconds, boolean running, int position) {
        this(jobName, name, cadence, status, lastRunAt, lateBySeconds, running, position, null);
    }

    public static final String ON_TIME = "ON_TIME";
    public static final String LATE = "LATE";
    public static final String OFF = "OFF";
}
