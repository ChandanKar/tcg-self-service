package com.tcgdigital.vmcontrol.model;

/**
 * VM status enumeration representing the state of a virtual machine.
 */
public enum VmStatus {
    RUNNING,
    STOPPED,
    STARTING,
    STOPPING,
    TERMINATED,   // VM was terminated/deleted in cloud
    NOT_FOUND,    // VM not found in cloud provider (may have been deleted)
    ERROR,
    UNKNOWN
}

