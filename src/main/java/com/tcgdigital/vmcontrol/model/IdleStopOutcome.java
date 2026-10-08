package com.tcgdigital.vmcontrol.model;

/** What one idle-stop evaluation decided (E16). */
public enum IdleStopOutcome {
    /** Dry run: everything in scope was idle; nothing was stopped. */
    WOULD_STOP,
    /** Enforce: a stop operation was started. */
    STOPPED,
    /** Not evaluated or not acted on: locked, operation running, production, inactive. */
    SKIPPED,
    /** An active snooze paused the rule. */
    SNOOZED
}
