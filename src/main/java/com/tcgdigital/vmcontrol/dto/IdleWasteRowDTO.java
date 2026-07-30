package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One row in the "Idle & waste, ranked by cost impact" table.
 */
public record IdleWasteRowDTO(
        String vmId,
        String vmName,
        String environmentName,
        String groupName,
        BigDecimal monthlyCost,
        Boolean costKnown,
        Integer idleDurationMinutes,
        Timestamp idleSince,
        BigDecimal latestCpuUtilization,
        BigDecimal monthlyIdleCost
) {}
