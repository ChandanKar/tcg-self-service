package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carbon by environment for one month (E37-T03), largest first, the unallocated remainder as its
 * own row, the attributed share (coverage) and a 12-month trend of attributed and unallocated kg.
 */
public record CarbonFleetDTO(String month, List<String> availableMonths, List<Row> rows, BigDecimal totalMbmKg,
                             BigDecimal totalLbmKg, BigDecimal coveragePct, List<TrendPoint> trend) {

    public record Row(String environmentId, String environmentName, BigDecimal mbmKg, BigDecimal lbmKg, BigDecimal cost,
                      BigDecimal kgPerDollar, boolean unallocated) {
    }

    public record TrendPoint(String month, BigDecimal attributedKg, BigDecimal unallocatedKg) {
    }
}
