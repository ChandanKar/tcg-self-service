package com.tcgdigital.vmcontrol.model;

/**
 * Access level for environment access.
 * Defines what operations a user can perform on an environment.
 */
public enum AccessLevel {

    /**
     * View-only access - can see environment and VMs but cannot perform operations.
     */
    VIEWER("viewer"),

    /**
     * User access - can perform VM operations and acquire locks.
     */
    USER("user"),

    /**
     * Admin access - full control over the environment including managing access.
     */
    ADMIN("admin");

    private final String value;

    AccessLevel(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse access level from string value.
     */
    public static AccessLevel fromValue(String value) {
        for (AccessLevel level : values()) {
            if (level.value.equalsIgnoreCase(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown access level: " + value);
    }

    /**
     * Check if this level has at least the required level of access.
     */
    public boolean hasAtLeast(AccessLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}

