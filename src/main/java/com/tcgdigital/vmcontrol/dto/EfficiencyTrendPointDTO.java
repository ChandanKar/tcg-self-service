package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of Cost Efficiency % (E17-T06). */
public record EfficiencyTrendPointDTO(LocalDate date, BigDecimal efficiencyPercent, BigDecimal potential,
                                      BigDecimal optimisable) {
}
