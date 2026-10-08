package com.tcgdigital.vmcontrol.service.idle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Why one VM is or is not idle over a rule's window (E16-T02). Stored as evidence with every
 * idle-stop event.
 *
 * @param idleSince       start of the VM's trailing run of idle samples; null if not idle now
 * @param maxCpu          highest CPU % in the window (null without samples)
 * @param networkBytes    network in + out over the window
 * @param diskBytes       disk read + write over the window (recorded, not a criterion)
 * @param coveragePercent share of the window covered by samples
 * @param reason          why the VM is not idle; null when it is
 */
public record VmIdleEvidence(
        String vmId,
        String vmName,
        boolean idle,
        Instant idleSince,
        BigDecimal maxCpu,
        long networkBytes,
        long diskBytes,
        int sampleCount,
        int coveragePercent,
        String reason
) {
}
