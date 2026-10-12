package com.tcgdigital.vmcontrol.service.provisioning;

/**
 * Where an engine's provisioning or destroy run is (E32-T02): {@code resourceId} is the engine's
 * handle on what it built (Service Catalog provisioned product, Terraform workspace).
 */
public record ProvisioningProgress(State state, String resourceId, String message) {

    public enum State { IN_PROGRESS, SUCCEEDED, FAILED }

    public boolean finished() {
        return state != State.IN_PROGRESS;
    }
}
