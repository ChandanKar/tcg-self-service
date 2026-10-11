package com.tcgdigital.vmcontrol.model;

/** Kinds of account-level waste the daily sweep finds (E22). */
public enum WasteFindingType {
    UNATTACHED_VOLUME,
    UNASSOCIATED_IP,
    GP2_VOLUME,
    LONG_STOPPED_VM,
    OLD_SNAPSHOT
}
