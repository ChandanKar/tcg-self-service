package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.WasteFindingType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Open waste per type and in total, estimated USD per month (E22-T03). {@code checked}: when the sweep
 * runs, e.g. "Daily 04:00" (UTC); {@code lastSweepAt}: when it last ran, scheduled or by hand (U15-T07).
 */
public record WasteSummaryDTO(boolean sweepEnabled, int openCount, BigDecimal monthlyTotal, List<TypeTotal> byType,
                              String checked, java.sql.Timestamp lastSweepAt) {
    public WasteSummaryDTO(boolean sweepEnabled, int openCount, BigDecimal monthlyTotal, List<TypeTotal> byType) {
        this(sweepEnabled, openCount, monthlyTotal, byType, null, null);
    }


    public record TypeTotal(WasteFindingType type, long count, BigDecimal monthly) {}
}
