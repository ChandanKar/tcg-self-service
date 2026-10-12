package com.tcgdigital.vmcontrol.model;

/**
 * Types of audit actions.
 */
public enum AuditAction {
    // Environment operations
    ENVIRONMENT_CREATED,
    ENVIRONMENT_UPDATED,
    ENVIRONMENT_DELETED,
    ENVIRONMENT_ACTIVATED,
    ENVIRONMENT_DEACTIVATED,

    // Group operations
    GROUP_CREATED,
    GROUP_UPDATED,
    GROUP_DELETED,
    GROUP_DEPENDENCIES_UPDATED,

    // VM operations
    VM_REGISTERED,
    VM_UPDATED,
    VM_DELETED,
    VM_START_REQUESTED,
    VM_STOP_REQUESTED,
    VM_RESTART_REQUESTED,
    VM_START_COMPLETED,
    VM_STOP_COMPLETED,
    VM_START_FAILED,
    VM_STOP_FAILED,
    VM_RESIZE_REQUESTED,
    VM_RESIZE_COMPLETED,
    VM_RESIZE_FAILED,
    /** A safe resize could not start on the new type and went back to the old one (E30-T06). */
    VM_RESIZE_ROLLED_BACK,
    /** An environment's Spot policy or a VM's Spot exclusion changed (E31-T03). */
    SPOT_POLICY_CHANGED,
    /** A node group's conversion to a Spot twin started, completed, failed, or was undone (E31-T05). */
    SPOT_CONVERSION_STARTED,
    SPOT_CONVERSION_COMPLETED,
    SPOT_CONVERSION_FAILED,
    SPOT_REVERTED,
    /** A Spot twin lost capacity and its on-demand standby was started, or it recovered (E31-T06). */
    SPOT_FALLBACK_ACTIVATED,
    SPOT_FALLBACK_CLEARED,
    /** AWS took a Spot instance or node group capacity back (E31-T07). */
    SPOT_INTERRUPTION,

    // Lock operations
    LOCK_ACQUIRED,
    LOCK_RELEASED,
    LOCK_BROKEN,
    LOCK_EXPIRED,
    LOCK_EXTENDED,

    // Environment leases (E15)
    LEASE_STARTED,
    LEASE_EXTENDED,
    LEASE_ENDED,
    LEASE_EXPIRED,
    LEASE_EXTENSION_REQUESTED,
    LEASE_EXTENSION_DECIDED,

    // Operation execution
    OPERATION_STARTED,
    OPERATION_COMPLETED,
    OPERATION_FAILED,
    OPERATION_CANCELLED,

    // Access operations
    ACCESS_REQUESTED,
    ACCESS_GRANTED,
    ACCESS_LEVEL_CHANGED,
    ACCESS_DENIED,
    ACCESS_REVOKED,

    // User operations
    USER_CREATED,
    USER_ONBOARDED,
    USER_UPDATED,
    USER_DEACTIVATED,
    USER_PROMOTED_TO_ADMIN,
    USER_PROMOTED_TO_ENV_ADMIN,
    USER_LOGIN_CONFLICT,

    // System operations
    SYSTEM_CONFIG_UPDATED,
    SCHEDULED_JOB_EXECUTED,
    STATE_DRIFT_DETECTED,
    VM_NAME_SYNCED,
    VM_DISCOVERED_UNTRACKED,
    VM_DEACTIVATED,
    VM_REACTIVATED,
    EKS_NODEGROUP_SYNC_FAILED,

    // Automation rule operations
    AUTOMATION_RULE_CREATED,
    AUTOMATION_RULE_UPDATED,
    AUTOMATION_RULE_DELETED,
    AUTOMATION_RULE_TRIGGERED,
    AUTOMATION_RULE_SKIPPED,
    AUTOMATION_RULE_FAILED,
    /** A holiday calendar or its dates changed (E19-T02). */
    HOLIDAY_CALENDAR_CHANGED,

    // Budgets (E20)
    BUDGET_CREATED,
    BUDGET_UPDATED,
    BUDGET_DELETED,
    BUDGET_ACTION_REQUESTED,
    BUDGET_ACTION_APPROVED,
    BUDGET_ACTION_REJECTED,
    BUDGET_FREEZE_LIFTED,
    BUDGET_FREEZE_OVERRIDDEN,

    /** An owner said whether a cost anomaly was real (E21-T03). */
    COST_ANOMALY_FEEDBACK,

    // Teams (E23)
    TEAM_CREATED,
    TEAM_UPDATED,
    TEAM_DEACTIVATED,

    // Shared-cost split rules (E23-T05)
    COST_SPLIT_RULE_CREATED,
    COST_SPLIT_RULE_UPDATED,
    COST_SPLIT_RULE_DELETED,
    /** A month's chargeback statement was frozen (E23-T07). */
    CHARGEBACK_FINALIZED,

    // Chat channels (E24-T02); details never carry the webhook URL
    CHAT_CHANNEL_CREATED,
    CHAT_CHANNEL_UPDATED,
    CHAT_CHANNEL_DELETED,
    /** A one-click chat action link was used (E24-T04); details say "via chat". */
    CHAT_ACTION_EXECUTED,
    /** A Jira issue was created for a recommendation or anomaly (E24-T06). */
    JIRA_TICKET_CREATED,

    // Approval policies (E25-T03)
    APPROVAL_POLICY_CREATED,
    APPROVAL_POLICY_UPDATED,
    APPROVAL_POLICY_DELETED,

    // Approval requests (E25-T04)
    APPROVAL_REQUESTED,
    APPROVAL_APPROVED,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED,
    APPROVAL_CANCELLED,
    /** A team's quotas changed (E25-T07). */
    TEAM_QUOTA_CHANGED,

    // AWS account registry (E28-T07)
    AWS_ACCOUNT_CREATED,
    AWS_ACCOUNT_UPDATED,
    AWS_ACCOUNT_DEACTIVATED,
    AWS_ACCOUNT_VERIFIED,

    // Waste sweeps (E22)
    WASTE_FINDING_DISMISSED,
    WASTE_FINDING_REOPENED,
    WASTE_ACTION_PROPOSED,
    WASTE_ACTION_APPROVED,
    WASTE_ACTION_REJECTED,
    WASTE_ACTION_EXECUTED,
    WASTE_ACTION_FAILED,
    WASTE_ACTION_ROLLED_BACK,

    // Idle auto-stop (E16)
    IDLE_STOP_RULE_CHANGED,
    IDLE_STOP_TRIGGERED,
    IDLE_STOP_SNOOZED
}

