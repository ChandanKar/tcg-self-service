package com.tcgdigital.vmcontrol.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised by the Microsoft Graph directory lookup used for admin user onboarding.
 * Carries the HTTP status the API should return plus a short machine-readable code.
 *
 * <ul>
 *   <li>{@link #disabled()} — {@code graph.directory.enabled} is off (or the Graph
 *       client is not wired): {@code 409} / {@code directory_lookup_disabled}. The
 *       caller falls back to manual onboarding.</li>
 *   <li>{@link #unavailable(String)} — Graph was called but failed (timeout, non-2xx,
 *       bad payload): {@code 502} / {@code directory_unavailable}.</li>
 * </ul>
 */
public class DirectoryLookupException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private DirectoryLookupException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static DirectoryLookupException disabled() {
        return new DirectoryLookupException(HttpStatus.CONFLICT, "directory_lookup_disabled",
                "Directory lookup is disabled. Onboard the user with manual entry instead.");
    }

    public static DirectoryLookupException unavailable(String detail) {
        return new DirectoryLookupException(HttpStatus.BAD_GATEWAY, "directory_unavailable",
                "Directory lookup is temporarily unavailable" + (detail != null ? ": " + detail : "") + ".");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
