package com.tcgdigital.vmcontrol.model;

/** What opened a lease (E15). */
public enum LeaseSource {
    /** A start operation on a lease-enabled environment. */
    START,
    /** A lock with an expiry on a lease-enabled environment. */
    LOCK
}
