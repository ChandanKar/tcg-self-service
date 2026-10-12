package com.tcgdigital.vmcontrol.model;

/** Where a request to provision an environment from a template is (E32-T01). */
public enum ProvisioningStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    PROVISIONING,
    READY,
    FAILED,
    DESTROYING,
    DESTROYED,
    CANCELLED
}
