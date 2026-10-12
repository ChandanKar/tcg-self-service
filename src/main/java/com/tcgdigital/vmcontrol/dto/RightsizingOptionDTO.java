package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One alternative of a rightsizing recommendation (E30-T05).
 *
 * @param kind {@code RIGHTSIZE} (a smaller or larger size, from the local rule or Compute
 *             Optimizer), {@code NEWER_GENERATION} (same architecture, newer family) or
 *             {@code GRAVITON} (the arm64 equivalent).
 * @param source {@code cpu-threshold-rule}, {@code newer-generation}, {@code graviton} or
 *               {@code compute-optimizer}.
 * @param performanceRisk Compute Optimizer's 0 (very low) .. 4 (very high); null for local options.
 * @param requiresArchitectureChange the option runs on another CPU architecture, so the VM needs
 *                                   an image built for it; such options are never applied from here.
 * @param applicable whether Apply may change the VM to this type; {@code applicableReason} says why not.
 */
public record RightsizingOptionDTO(
        String instanceType,
        String kind,
        String source,
        String architecture,
        BigDecimal monthlyCostAfter,
        BigDecimal monthlySavings,
        BigDecimal performanceRisk,
        boolean requiresArchitectureChange,
        boolean applicable,
        String applicableReason
) {
}
