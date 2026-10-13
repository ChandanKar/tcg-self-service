package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An environment's attributed carbon emissions (E37-T03), last 12 months oldest first, with the
 * month's cost (actual where Cost Explorer has it, else the estimate) and kg CO2e per dollar.
 */
public record EnvironmentCarbonDTO(String environmentId, List<Month> months, String latestMonth, BigDecimal latestMbmKg,
                                   BigDecimal latestLbmKg, String coverageNote) {

    public record Month(String month, BigDecimal mbmKg, BigDecimal lbmKg, BigDecimal cost, BigDecimal kgPerDollar) {
    }
}
