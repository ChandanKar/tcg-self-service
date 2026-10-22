package com.tcgdigital.vmcontrol.exception;

/**
 * An AWS call that would need a region nobody stored (U19-T04): the app no longer guesses
 * aws.region, so the call is refused (HTTP 409) and the message says what to set.
 */
public class RegionUnknownException extends ConflictException {

    public RegionUnknownException(String message) {
        super(message, "REGION_UNKNOWN");
    }
}
