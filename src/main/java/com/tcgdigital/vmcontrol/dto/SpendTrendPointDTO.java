package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Date;

/**
 * One point on the spend trend chart. {@code actualCost} is reserved for a future real-billing
 * provider and is always null today — the trend chart renders an estimated-only line until then.
 */
public record SpendTrendPointDTO(
        Date date,
        BigDecimal estimatedCost,
        BigDecimal actualCost
) {}
