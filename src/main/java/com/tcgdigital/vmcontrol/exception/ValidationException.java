package com.tcgdigital.vmcontrol.exception;

/**
 * Exception thrown when a validation error occurs. An optional {@code code} names the case for
 * clients that show different states (U09-T08: LINK_EXPIRED, LINK_INVALID); it is added to the
 * error body when set.
 */
public class ValidationException extends RuntimeException {

    private final String code;

    public ValidationException(String message) {
        super(message);
        this.code = null;
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public ValidationException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
