package com.tcgdigital.vmcontrol.model;

/** State of a request to extend a lease beyond the environment's maximum (E15-T04). */
public enum LeaseExtensionStatus {
    PENDING,
    APPROVED,
    DENIED,
    /** The lease ended before anyone decided. */
    EXPIRED
}
