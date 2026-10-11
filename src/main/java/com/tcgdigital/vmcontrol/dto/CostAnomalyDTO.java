package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A cost anomaly as the pages show it (E21-T04). {@code canGiveFeedback}: the caller owns the
 * environment (or is an admin).
 */
public record CostAnomalyDTO(
        String anomalyId,
        String environmentId,
        String environmentName,
        LocalDate anomalyStart,
        LocalDate anomalyEnd,
        BigDecimal totalImpact,
        BigDecimal totalExpected,
        BigDecimal maxScore,
        String topCause,
        String feedback,
        boolean canGiveFeedback) {
}
