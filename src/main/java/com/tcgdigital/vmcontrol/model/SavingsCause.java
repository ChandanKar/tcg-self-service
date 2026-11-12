package com.tcgdigital.vmcontrol.model;

/** Why a VM was not running, for savings attribution (E17-T02). */
public enum SavingsCause {
    SCHEDULE,
    LEASE,
    IDLE_STOP,
    MANUAL_STOP,
    RIGHTSIZING,
    /** Stops the platform made on its own: access automation, budget actions, system runs (U22-T02). */
    AUTOMATION,
    /**
     * A VM that was off without the platform stopping it (a stop seen only by state sync, or never
     * started through the platform). Kept in the ledger but not a saving: SavingsReportService leaves
     * it out of every total (U22-T02).
     */
    OTHER;

    /** The cause of a stop started by an operation; a stop seen only by state sync is OTHER. */
    public static SavingsCause fromTrigger(OperationTriggerSource source) {
        if (source == null) return OTHER;
        return switch (source) {
            case SCHEDULE -> SCHEDULE;
            case LEASE -> LEASE;
            case IDLE_STOP -> IDLE_STOP;
            case MANUAL -> MANUAL_STOP;
            case ACCESS_AUTOMATION, BUDGET, SYSTEM -> AUTOMATION;
        };
    }
}
