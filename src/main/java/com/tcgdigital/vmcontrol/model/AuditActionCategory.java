package com.tcgdigital.vmcontrol.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The action groups My activity filters by (U09-T04): starting and stopping (operations, VM steps,
 * leases and held approvals), locks, and access. Each maps to a fixed set of {@link AuditAction}s.
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
            AuditAction.ACCESS_DENIED, AuditAction.ACCESS_REVOKED));

    private final Set<AuditAction> actions;

    AuditActionCategory(Set<AuditAction> actions) {
        this.actions = actions;
    }

    public Set<AuditAction> actions() {
        return actions;
    }

    /** The stored names, for a {@code IN (:actions)} query. Never empty. */
    public List<String> actionNames() {
        return actions.stream().map(Enum::name).toList();
    }
}
