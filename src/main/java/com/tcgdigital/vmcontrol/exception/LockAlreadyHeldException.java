package com.tcgdigital.vmcontrol.exception;

/**
 * Exception thrown when attempting to acquire a lock that is already held.
 */
public class LockAlreadyHeldException extends RuntimeException {

    private final String environmentId;
    private final String lockedByUserId;

    public LockAlreadyHeldException(String message) {
        super(message);
        this.environmentId = null;
        this.lockedByUserId = null;
    }

    public LockAlreadyHeldException(String message, String environmentId, String lockedByUserId) {
        super(message);
        this.environmentId = environmentId;
        this.lockedByUserId = lockedByUserId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getLockedByUserId() {
        return lockedByUserId;
    }
}

