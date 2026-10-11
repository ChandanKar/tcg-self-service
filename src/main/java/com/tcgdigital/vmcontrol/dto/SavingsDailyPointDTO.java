package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Net savings of one cause on one day (E17-T07 chart). */
public record SavingsDailyPointDTO(LocalDate date, String cause, String label, BigDecimal net) {
}
