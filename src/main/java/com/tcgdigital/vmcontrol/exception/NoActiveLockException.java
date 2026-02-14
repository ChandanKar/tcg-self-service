package com.tcgdigital.vmcontrol.exception;

/**
 * Exception thrown when no active lock exists for an operation that requires one.
 */
public class NoActiveLockException extends RuntimeException {

    private final String environmentId;

    public NoActiveLockException(String message) {
        super(message);
        this.environmentId = null;
    }

    public NoActiveLockException(String message, String environmentId) {
        super(message);
        this.environmentId = environmentId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }
}

