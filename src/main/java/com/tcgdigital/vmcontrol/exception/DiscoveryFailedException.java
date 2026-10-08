package com.tcgdigital.vmcontrol.exception;

/**
 * An EC2 discovery call failed (throttling, credentials, network). Callers must treat the
 * result as unknown, not as "no instances", so nothing is flagged missing (H24).
 */
public class DiscoveryFailedException extends RuntimeException {

    public DiscoveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
