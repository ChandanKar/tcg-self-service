package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * What one VM costs and whether it could be another size (U08-T06), for its details panel and for
 * everyone who can view the VM. It carries no instance ID: only the VM's own figures.
 *
 * @param monthToDateCost estimated cost since the 1st of this month; null when no price is known
 * @param hourlyRate      the price per hour now; null when no price is known
 * @param priceApproximate the price is another region's or otherwise approximate
 * @param purchaseOption  ON_DEMAND or SPOT
 * @param rightsizing     the size the platform recommends for this VM, or null without one
 */
public record VmAdviceDTO(BigDecimal monthToDateCost, BigDecimal hourlyRate, boolean priceApproximate,
                          String purchaseOption, Rightsizing rightsizing) {

    /**
     * @param direction       SCALE_DOWN, SCALE_UP or MODERNIZE
     * @param monthlySavings  estimated saving a month (negative for a scale-up); null when unpriced
     * @param cpuPeak         peak CPU % over the cost window
     * @param memoryPeak      peak memory % (null without the CloudWatch agent)
     * @param source          cpu-threshold-rule, compute-optimizer or newer-generation
     * @param optimizerAgrees AWS Compute Optimizer points the same way
     */
    public record Rightsizing(String currentType, String recommendedType, String direction, BigDecimal monthlySavings,
                              BigDecimal cpuPeak, BigDecimal memoryPeak, String source, boolean optimizerAgrees) {
    }
}
