package com.tcgdigital.vmcontrol.model;

/** What a schedule rule does on a day of its holiday calendar (E19-T02). */
public enum HolidayBehavior {
    /** Do not start on holidays; stops still run. */
    SKIP_START,
    /** Neither start nor stop on holidays. */
    SKIP_ALL
}
