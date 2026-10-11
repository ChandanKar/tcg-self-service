package com.tcgdigital.vmcontrol.exception;

import org.springframework.http.HttpStatus;

/**
 * An outside system (Jira, E24-T06) refused or failed a request. Carries the status the API
 * returns (409 when the integration is off, 502 when the other side failed) and a short code.
 * Messages never contain credentials.
 */
public class IntegrationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public IntegrationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
