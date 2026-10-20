package com.tcgdigital.vmcontrol.exception;

/**
 * The request lost a race or conflicts with the current state (HTTP 409), e.g. an access
 * request that another reviewer has already decided. An optional {@code code} names the case
 * (U09-T08: LINK_USED); it is added to the error body when set.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        super(message);
        this.code = null;
    }

    public ConflictException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
