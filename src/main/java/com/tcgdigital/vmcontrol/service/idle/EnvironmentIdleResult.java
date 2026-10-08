package com.tcgdigital.vmcontrol.service.idle;

import java.time.Instant;
import java.util.List;

/**
 * Idle state of a rule's scope (E16-T02).
 *
 * @param allIdle   every running VM in scope is idle over the whole window
 * @param idleSince when the scope became idle: the latest of the VMs' idle-since times
 * @param vms       evidence per running VM in scope (empty when nothing is running)
 */
public record EnvironmentIdleResult(boolean allIdle, Instant idleSince, List<VmIdleEvidence> vms) {

    public boolean nothingRunning() {
        return vms.isEmpty();
    }
}
