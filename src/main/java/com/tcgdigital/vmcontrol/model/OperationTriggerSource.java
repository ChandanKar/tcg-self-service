package com.tcgdigital.vmcontrol.model;

/**
 * What started an operation (E17-T01), so savings can be attributed by cause. Clients never
 * choose it: the code path that starts the operation does.
 */
public enum OperationTriggerSource {
    /** A user clicked Start/Stop. */
    MANUAL,
    /** A calendar automation rule. */
    SCHEDULE,
    /** An access-grant or lock-acquire automation rule. */
    ACCESS_AUTOMATION,
    /** A lease reached its end (E15). */
    LEASE,
    /** Idle auto-stop (E16). */
    IDLE_STOP,
    /** A budget action. */
    BUDGET,
    /** Any other platform action. */
    SYSTEM
}
