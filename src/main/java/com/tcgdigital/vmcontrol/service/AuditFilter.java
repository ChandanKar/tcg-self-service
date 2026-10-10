package com.tcgdigital.vmcontrol.service;

import java.sql.Timestamp;
import java.util.Collection;

/**
 * Every audit-log filter at once (E11-T04, H19): all non-null criteria apply together.
 * {@code allowedEnvironmentIds} null means unrestricted; otherwise only rows of those
 * environments match. {@code to} is exclusive.
 */
public record AuditFilter(
        Collection<String> allowedEnvironmentIds,
        String environmentId,
        String userId,
        String action,
        Boolean success,
        Timestamp from,
        Timestamp to,
        String text
) {
}
