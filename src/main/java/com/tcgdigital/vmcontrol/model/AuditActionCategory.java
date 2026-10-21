package com.tcgdigital.vmcontrol.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Groups of audit actions (U09-T04, U12-T01): My activity filters by the first three, the audit log
 * by all of them. Starting and stopping (operations, VM steps, leases and held approvals), locks,
 * access (grants and people), settings (environments, groups, VMs and the platform's configuration),
 * automation (rules and holiday calendars) and cost (budgets, waste, chargeback). Each action is in
 * at most one group; some (resizes, Spot moves, provisioning, sync events) are in none.
 */
public enum AuditActionCategory {
    OPERATIONS(EnumSet.of(
            AuditAction.OPERATION_STARTED, AuditAction.OPERATION_COMPLETED, AuditAction.OPERATION_FAILED,
            AuditAction.OPERATION_CANCELLED,
            AuditAction.VM_START_REQUESTED, AuditAction.VM_STOP_REQUESTED, AuditAction.VM_RESTART_REQUESTED,
            AuditAction.VM_START_COMPLETED, AuditAction.VM_STOP_COMPLETED,
            AuditAction.VM_START_FAILED, AuditAction.VM_STOP_FAILED,
            AuditAction.ENVIRONMENT_ACTIVATED, AuditAction.ENVIRONMENT_DEACTIVATED,
            AuditAction.LEASE_STARTED, AuditAction.LEASE_EXTENDED, AuditAction.LEASE_ENDED, AuditAction.LEASE_EXPIRED,
            AuditAction.LEASE_EXTENSION_REQUESTED, AuditAction.LEASE_EXTENSION_DECIDED,
            AuditAction.APPROVAL_REQUESTED, AuditAction.APPROVAL_APPROVED, AuditAction.APPROVAL_DENIED,
            AuditAction.APPROVAL_EXPIRED, AuditAction.APPROVAL_CANCELLED)),
    LOCKS(EnumSet.of(
            AuditAction.LOCK_ACQUIRED, AuditAction.LOCK_RELEASED, AuditAction.LOCK_BROKEN,
            AuditAction.LOCK_EXPIRED, AuditAction.LOCK_EXTENDED)),
    ACCESS(EnumSet.of(
            AuditAction.ACCESS_REQUESTED, AuditAction.ACCESS_GRANTED, AuditAction.ACCESS_LEVEL_CHANGED,
            AuditAction.ACCESS_DENIED, AuditAction.ACCESS_REVOKED,
            AuditAction.USER_CREATED, AuditAction.USER_ONBOARDED, AuditAction.USER_UPDATED, AuditAction.USER_DEACTIVATED,
            AuditAction.USER_PROMOTED_TO_ADMIN, AuditAction.USER_PROMOTED_TO_ENV_ADMIN, AuditAction.USER_LOGIN_CONFLICT)),
    SETTINGS(EnumSet.of(
            AuditAction.ENVIRONMENT_CREATED, AuditAction.ENVIRONMENT_UPDATED, AuditAction.ENVIRONMENT_DELETED,
            AuditAction.GROUP_CREATED, AuditAction.GROUP_UPDATED, AuditAction.GROUP_DELETED,
            AuditAction.GROUP_DEPENDENCIES_UPDATED,
            AuditAction.VM_REGISTERED, AuditAction.VM_UPDATED, AuditAction.VM_DELETED,
            AuditAction.VM_DEACTIVATED, AuditAction.VM_REACTIVATED,
            AuditAction.SPOT_POLICY_CHANGED, AuditAction.IDLE_STOP_RULE_CHANGED,
            AuditAction.APPROVAL_POLICY_CREATED, AuditAction.APPROVAL_POLICY_UPDATED, AuditAction.APPROVAL_POLICY_DELETED,
            AuditAction.WEBHOOK_CREATED, AuditAction.WEBHOOK_UPDATED, AuditAction.WEBHOOK_DELETED,
            AuditAction.CHAT_CHANNEL_CREATED, AuditAction.CHAT_CHANNEL_UPDATED, AuditAction.CHAT_CHANNEL_DELETED,
            AuditAction.TEMPLATE_CREATED, AuditAction.TEMPLATE_UPDATED, AuditAction.TEMPLATE_RETIRED,
            AuditAction.TEAM_CREATED, AuditAction.TEAM_UPDATED, AuditAction.TEAM_DEACTIVATED, AuditAction.TEAM_QUOTA_CHANGED,
            AuditAction.AWS_ACCOUNT_CREATED, AuditAction.AWS_ACCOUNT_UPDATED, AuditAction.AWS_ACCOUNT_DEACTIVATED,
            AuditAction.AWS_ACCOUNT_VERIFIED,
            AuditAction.API_TOKEN_CREATED, AuditAction.API_TOKEN_REVOKED,
            AuditAction.SERVICE_ACCOUNT_CREATED, AuditAction.SERVICE_ACCOUNT_DEACTIVATED,
            AuditAction.SYSTEM_CONFIG_UPDATED)),
    AUTOMATION(EnumSet.of(
            AuditAction.AUTOMATION_RULE_CREATED, AuditAction.AUTOMATION_RULE_UPDATED, AuditAction.AUTOMATION_RULE_DELETED,
            AuditAction.AUTOMATION_RULE_TRIGGERED, AuditAction.AUTOMATION_RULE_SKIPPED, AuditAction.AUTOMATION_RULE_FAILED,
            AuditAction.HOLIDAY_CALENDAR_CHANGED)),
    COST(EnumSet.of(
            AuditAction.BUDGET_CREATED, AuditAction.BUDGET_UPDATED, AuditAction.BUDGET_DELETED,
            AuditAction.BUDGET_ACTION_REQUESTED, AuditAction.BUDGET_ACTION_APPROVED, AuditAction.BUDGET_ACTION_REJECTED,
            AuditAction.BUDGET_FREEZE_LIFTED, AuditAction.BUDGET_FREEZE_OVERRIDDEN,
            AuditAction.COST_ANOMALY_FEEDBACK,
            AuditAction.COST_SPLIT_RULE_CREATED, AuditAction.COST_SPLIT_RULE_UPDATED, AuditAction.COST_SPLIT_RULE_DELETED,
            AuditAction.CHARGEBACK_FINALIZED,
            AuditAction.WASTE_FINDING_DISMISSED, AuditAction.WASTE_FINDING_REOPENED, AuditAction.WASTE_ACTION_PROPOSED,
            AuditAction.WASTE_ACTION_APPROVED, AuditAction.WASTE_ACTION_REJECTED, AuditAction.WASTE_ACTION_EXECUTED,
            AuditAction.WASTE_ACTION_FAILED, AuditAction.WASTE_ACTION_ROLLED_BACK));

    private final Set<AuditAction> actions;

    AuditActionCategory(Set<AuditAction> actions) {
        this.actions = actions;
    }

    /** The name the audit log shows: "Start and stop", "Locks", "Access", "Settings", "Automation", "Cost". */
    public String label() {
        return switch (this) {
            case OPERATIONS -> "Start and stop";
            case LOCKS -> "Locks";
            case ACCESS -> "Access";
            case SETTINGS -> "Settings";
            case AUTOMATION -> "Automation";
            case COST -> "Cost";
        };
    }

    public Set<AuditAction> actions() {
        return actions;
    }

    /** The stored names, for a {@code IN (:actions)} query. Never empty. */
    public List<String> actionNames() {
        return actions.stream().map(Enum::name).toList();
    }
}
