package com.tcgdigital.vmcontrol.dto;

/**
 * VMs that need a look in one environment (U11-T02): drifted (the cloud state differs from the
 * platform's) and pending (found by discovery, not in a group yet). The registry banner adds them up.
 */
public record RegistryReviewSummaryDTO(String environmentId, String name, long drift, long pending) {
}
