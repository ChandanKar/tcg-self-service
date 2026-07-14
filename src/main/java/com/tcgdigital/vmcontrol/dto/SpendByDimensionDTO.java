package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One bar in a spend breakdown chart — reused for spend-by-environment, spend-by-VM-type, and
 * spend-by-team, since all three are the same shape (a dimension key against a cost total).
 */
public record SpendByDimensionDTO(
        String dimensionKey,
        String dimensionLabel,
        BigDecimal cost,
        Integer vmCount
) {}
