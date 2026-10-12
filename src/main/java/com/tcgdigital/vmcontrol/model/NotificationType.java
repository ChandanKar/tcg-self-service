package com.tcgdigital.vmcontrol.model;

public enum NotificationType {
    LOCK_ACQUIRED,
    LOCK_RELEASED,
    LOCK_BROKEN,
    LOCK_EXPIRED,
    LOCK_EXPIRING,
    ACCESS_REQUESTED,
    ACCESS_GRANTED,
    ACCESS_LEVEL_CHANGED,
    ACCESS_REVOKED,
    ACCESS_REQUEST_APPROVED,
    ACCESS_REQUEST_DENIED,
    ACCESS_EXPIRING,
    ACCESS_EXPIRED,
    OPERATION_REQUESTED,
    OPERATION_COMPLETED,
    OPERATION_FAILED,
    STATE_DRIFT_DETECTED,
    EKS_SYNC_CHANGED,
    AUTOMATION_RULE_SKIPPED,
    WEEKLY_COST_REPORT,
    WEEKLY_IDLE_WASTE_REPORT,
    WEEKLY_RIGHTSIZING_REPORT,
    ENVIRONMENT_STOP_NOTICE,
    IDLE_AUTO_STOPPED,
    LEASE_EXPIRING,
    LEASE_EXPIRED,
    LEASE_EXTENSION_REQUESTED,
    LEASE_EXTENSION_DECIDED,
    /** A budget's spend reached one of its thresholds (E20-T02). */
    BUDGET_THRESHOLD,
    /** A budget's month-end forecast exceeds it (E20-T02). */
    BUDGET_FORECAST,
    /** An exhausted budget's action awaits an owner's approval (E20-T03). */
    BUDGET_ACTION_PENDING,
    /** Starts are frozen in an environment by its budget (E20-T03). */
    BUDGET_FREEZE,
    /** AWS flagged unexpected spend in an environment (E21-T03). */
    COST_ANOMALY,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_DENIED,
    QUOTA_THRESHOLD,
    /** AWS reclaimed a Spot VM or a Spot node group lost capacity (E31-T07). */
    SPOT_INTERRUPTED,
    /** A request for an environment from a template waits for approval, or was decided (E32-T03). */
    PROVISIONING_REQUESTED,
    PROVISIONING_APPROVED,
    PROVISIONING_REJECTED,
    /** A requested environment is ready, or could not be built (E32-T04). */
    PROVISIONING_READY,
    PROVISIONING_FAILED,
    /** A provisioned environment will be destroyed soon, or was destroyed (E32-T05). */
    ENVIRONMENT_DESTROY_WARNING,
    ENVIRONMENT_DESTROYED
}
