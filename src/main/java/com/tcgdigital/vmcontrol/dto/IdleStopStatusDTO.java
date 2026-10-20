package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.IdleStopOutcome;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * Idle auto-stop state of an environment for its page (E16-T04): rules, snooze, the latest
 * decision and what the last {@code days} days would have saved / saved.
 */
public record IdleStopStatusDTO( // canOperate: may snooze; canAdminister: may change rules
        boolean featureEnabled,
        boolean production,
        boolean canOperate,
        boolean canAdminister,
        List<IdleStopRuleDTO> rules,
        Timestamp snoozedUntil,
        String snoozedByUserId,
        String snoozedByDisplayName,
        LatestEvent latestEvent,
        int days,
        int wouldStopEpisodes,
        BigDecimal wouldHaveSaved,
        int stoppedCount,
        BigDecimal savedEstimate,
        List<SavingEntry> stoppedSavings,
        /** The 5 newest decisions, newest first (U08-T09: "How it is going"). */
        List<LatestEvent> recentEvents,
        /** Days of dry run before a rule may stop VMs (automation.idle-stop.min-dry-run-days). */
        long minDryRunDays,
        /** The caller may switch a rule to stopping before the dry run is over (administrators). */
        boolean canEnforceEarly
) {
    public record LatestEvent(IdleStopOutcome outcome, String reason, Timestamp evaluatedAt, Timestamp idleSince) {}

    /** Hours avoided by one STOPPED event and their value (for a later savings ledger, E17). */
    public record SavingEntry(String idleStopEventId, BigDecimal hours, BigDecimal amount) {}
}
