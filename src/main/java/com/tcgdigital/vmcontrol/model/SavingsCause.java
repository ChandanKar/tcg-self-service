package com.tcgdigital.vmcontrol.model;

/** Why a VM was not running, for savings attribution (E17-T02). */
public enum SavingsCause {
    SCHEDULE,
    LEASE,
    IDLE_STOP,
    MANUAL_STOP,
    RIGHTSIZING,
    OTHER;

    /** The cause of a stop started by an operation; a stop seen only by state sync is OTHER. */
    public static SavingsCause fromTrigger(OperationTriggerSource source) {
        if (source == null) return OTHER;
        return switch (source) {
            case SCHEDULE -> SCHEDULE;
            case LEASE -> LEASE;
            case IDLE_STOP -> IDLE_STOP;
            case MANUAL -> MANUAL_STOP;
            case ACCESS_AUTOMATION, BUDGET, SYSTEM -> OTHER;
        };
    }
}
