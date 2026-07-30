package com.tcgdigital.vmcontrol.model;

/**
 * Sub-mode for an ACCESS_GRANT AutomationRule — when exactly it fires.
 */
public enum AccessGrantMode {
    /** Fires the moment a user acquires the EnvironmentLock (starts a work session). */
    LOCK_ACQUIRE,
    /** Fires when an access request is approved or access is granted directly. */
    ACCESS_APPROVED
}
