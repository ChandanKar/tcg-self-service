package com.tcgdigital.vmcontrol.model;

/** Where a waste finding stands (E22). */
public enum WasteFindingStatus {
    /** Still there, nobody has decided. */
    OPEN,
    /** An admin decided it is not waste; a sweep never reopens it. */
    DISMISSED,
    /** No longer seen by a successful sweep, or fixed. */
    RESOLVED,
    /** A fix action awaits approval or runs (E22-T04). */
    ACTION_PENDING
}
