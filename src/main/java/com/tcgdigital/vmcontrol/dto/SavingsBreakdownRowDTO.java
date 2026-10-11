package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/** One cause, environment or team in a savings breakdown (E17-T06). */
public record SavingsBreakdownRowDTO(String key, String label, BigDecimal net, BigDecimal gross, BigDecimal hours) {
}
