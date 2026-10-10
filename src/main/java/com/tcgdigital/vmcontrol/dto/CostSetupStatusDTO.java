package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/**
 * The Cost Setup page (E08-T11): each cost feature's switch, last run and, after a pre-flight
 * check, whether the AWS credential may call what it needs. {@code principalArn},
 * {@code checkedAt} and {@code probes} are filled only by a check; feature checks are then
 * ALLOWED, DENIED or UNKNOWN.
 */
public record CostSetupStatusDTO(
        boolean credentialsConfigured,
        String principalArn,
        Timestamp checkedAt,
        List<Feature> features,
        List<Probe> probes
) {
    /** One cost feature. {@code extra} holds feature-specific figures (e.g. tagged VM counts). */
    public record Feature(String key, String label, boolean enabled, String property, String envVar,
                          Timestamp lastRunAt, java.util.Map<String, Object> extra, List<Check> checks) {
    }

    /** One IAM action: ALLOWED, DENIED or UNKNOWN, with a hint when not ALLOWED. */
    public record Check(String action, String result, String hint) {
    }

    /** A free (or one-call) live probe, e.g. Compute Optimizer enrollment. */
    public record Probe(String name, String result, String detail) {
    }
}
