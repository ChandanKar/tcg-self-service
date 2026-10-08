package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One environment in the My Account cost list (E18-T02). From daily snapshots only (no live
 * estimate), so the list costs a fixed number of queries.
 *
 * @param scope     FULL, or GROUPS for a group-only grant (then the cost fields are null and
 *                  {@code hint} says why)
 * @param myLevel   the user's effective environment level (ADMIN / USER / VIEWER), or null for
 *                  a group-only grant
 * @param owner     effective level ADMIN
 * @param sparkline 14 daily estimates, oldest first (0 for a day without a snapshot)
 */
public record MyEnvironmentCostDTO(
        String environmentId,
        String name,
        String displayName,
        String scope,
        String myLevel,
        boolean owner,
        BigDecimal monthToDateEstimated,
        BigDecimal changePercent,
        List<BigDecimal> sparkline,
        int runningVmCount,
        int totalVmCount,
        int scheduleRuleCount,
        Instant nextStop,
        Instant nextStart,
        Instant leaseEndsAt,
        String hint
) {
    /** The list; admins see at most {@code limit} environments, highest month to date first. */
    public record Response(List<MyEnvironmentCostDTO> environments, boolean capped, int totalEnvironments, int limit) {}
}
