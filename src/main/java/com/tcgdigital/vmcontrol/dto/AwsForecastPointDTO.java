package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Date;

/** One AWS forecast point (E35-T02): its period (end exclusive), the mean and the prediction interval. */
public record AwsForecastPointDTO(Date periodStart, Date periodEnd, BigDecimal mean, BigDecimal lower, BigDecimal upper) {
}
