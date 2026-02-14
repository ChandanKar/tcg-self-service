package com.tcgdigital.vmcontrol.model;

/**
 * Status for environment access requests.
 */
public enum AccessRequestStatus {

    /**
     * Request is pending review.
     */
    PENDING("pending"),

    /**
     * Request has been approved.
     */
    APPROVED("approved"),

    /**
     * Request has been denied.
     */
    DENIED("denied"),

    /**
     * Request was cancelled by the requester.
     */
    CANCELLED("cancelled");

    private final String value;

    AccessRequestStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse status from string value.
     */
    public static AccessRequestStatus fromValue(String value) {
        for (AccessRequestStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown access request status: " + value);
    }
}

