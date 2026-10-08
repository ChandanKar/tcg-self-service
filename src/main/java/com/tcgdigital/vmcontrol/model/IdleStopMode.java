package com.tcgdigital.vmcontrol.model;

/** DRY_RUN records what an idle stop would have done; ENFORCE stops the VMs (E16). */
public enum IdleStopMode {
    DRY_RUN,
    ENFORCE
}
