package com.tcgdigital.vmcontrol.model;

/** Where a safe resize is (E30-T06); COMPLETED, ROLLED_BACK and FAILED are final. */
public enum ResizeStatus {
    REQUESTED,
    STOPPING,
    RESIZING,
    STARTING,
    COMPLETED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
}
