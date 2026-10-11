package com.tcgdigital.vmcontrol.model;

/** Lifecycle of an environment lease (E15). */
public enum LeaseStatus {
    /** The environment is running under this lease; at most one per environment. */
    ACTIVE,
    /** Ended by a user (e.g. the whole environment was stopped). */
    ENDED,
    /** Reached its end time; the platform stopped (or tried to stop) the environment. */
    EXPIRED
}
