package com.tcgdigital.vmcontrol.model;

/**
 * Why an automation rule's last run ended as it did (V31). The status says what happened
 * (SUCCESS / SKIPPED / FAILED); the reason says why.
 */
public enum AutomationRunReason {
    /** Started an operation. */
    OK,
    /** Every target was already in the requested state. */
    NOTHING_TO_DO,
    /** The environment is locked by someone else. */
    LOCKED,
    /** Another operation is running in the environment. */
    OPERATION_IN_PROGRESS,
    /** The environment was deactivated; the rule was disabled. */
    ENVIRONMENT_INACTIVE,
    /** The rule's creator is inactive or no longer an admin; the rule was disabled. */
    CREATOR_INACTIVE,
    /** The rule's group or VM no longer exists; the rule was disabled. */
    SCOPE_MISSING,
    /** The operation could not be started. */
    ERROR,
    /** The schedule window passed without a successful run. */
    MISSED_WINDOW
}
