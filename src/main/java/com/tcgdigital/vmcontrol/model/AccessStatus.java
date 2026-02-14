package com.tcgdigital.vmcontrol.model;

/**
 * Status for environment access grants.
 */
public enum AccessStatus {

    /**
     * Access is pending approval.
     */
    PENDING("pending"),

    /**
     * Access is currently active.
     */
    ACTIVE("active"),

    /**
     * Access has been revoked.
     */
    REVOKED("revoked"),

    /**
     * Access has expired.
     */
    EXPIRED("expired");

    private final String value;

    AccessStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse status from string value.
     */
    public static AccessStatus fromValue(String value) {
        for (AccessStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown access status: " + value);
    }
}

