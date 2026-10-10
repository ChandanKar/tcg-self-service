package com.tcgdigital.vmcontrol.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Where a cost day starts and ends (E08-T01). Cost Explorer reports whole UTC days, so the daily
 * snapshot uses the same boundary ({@code cost.day-boundary-zone}, default UTC) regardless of the
 * server's own timezone.
 */
@Component
public class CostDayBoundary {

    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public CostDayBoundary(@Value("${cost.day-boundary-zone:UTC}") String zone) {
        this(zone, Clock.systemUTC());
    }

    CostDayBoundary(String zone, Clock clock) {
        this.zone = ZoneId.of(zone);
        this.clock = clock;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The current cost day. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The last complete cost day. */
    public LocalDate yesterday() {
        return today().minusDays(1);
    }

    /** The instant {@code day} starts in the cost zone. */
    public Timestamp startOf(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(zone).toInstant());
    }
}
