package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What starting VMs for a number of hours would cost (E25-T01). Each VM's cost is rounded to
 * cents and then summed, so estimatedTotal is the sum of the VM lines; hourlyTotal is the sum of
 * the hourly rates rounded to cents. VMs already running add nothing and are not listed; VMs
 * without a known price add 0 and are counted in unknownPriceCount.
 */
public record CostPreviewDTO(
        int hours,
        BigDecimal hourlyTotal,
        BigDecimal estimatedTotal,
        String currency,
        List<VmLine> vms,
        int unknownPriceCount,
        int alreadyRunningCount,
        /** Some rate came from another region (E08-T07). */
        boolean approximate) {

    public record VmLine(String vmId, String name, String instanceType, int nodeCount, BigDecimal hourlyRate,
                         BigDecimal estimatedCost, boolean priceKnown, boolean approximate) {}
}
