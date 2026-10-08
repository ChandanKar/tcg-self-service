package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.VmStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether a VM in STARTING/STOPPING is plausibly still driven by a real in-flight operation, so
 * state sync and EKS sync leave it alone (E09-T07). Shared so both syncs use the same rule.
 */
@Component
public class VmTransitionalGuard {

    /**
     * The longest legitimate single-VM operation timeout in this app is 15 minutes (EKS node
     * group scaling); this threshold is set comfortably above that so sync never interrupts a
     * real operation, while still eventually reconciling a VM whose status update was orphaned
     * by a crashed/failed operation that never wrote back a terminal status.
     */
    @Value("${vm.state.sync.stale-transitional-minutes:20}")
    private long staleTransitionalMinutes = 20;

    /**
     * True if the status is STARTING/STOPPING and was entered less than
     * {@code vm.state.sync.stale-transitional-minutes} ago ({@code updatedAt} dates the last
     * status change, so sync writes must not bump it).
     */
    public boolean isFreshTransitional(VmStatus status, Timestamp updatedAt) {
        if (status != VmStatus.STARTING && status != VmStatus.STOPPING) {
            return false;
        }
        if (updatedAt == null) {
            return false;
        }
        long minutesInState = Duration.between(updatedAt.toInstant(), Instant.now()).toMinutes();
        return minutesInState < staleTransitionalMinutes;
    }

    public long getStaleTransitionalMinutes() {
        return staleTransitionalMinutes;
    }
}
