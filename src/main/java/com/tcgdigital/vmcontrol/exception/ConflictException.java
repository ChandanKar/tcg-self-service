package com.tcgdigital.vmcontrol.exception;

/**
 * The request lost a race or conflicts with the current state (HTTP 409), e.g. an access
 * request that another reviewer has already decided.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
