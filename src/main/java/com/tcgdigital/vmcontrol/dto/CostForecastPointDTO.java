package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Date;

/**
 * One point on the cost forecast chart — either a real historical daily total
 * ({@code isForecast=false}) or a projected future value ({@code isForecast=true}). The two are
 * never conflated on the frontend (rendered as solid vs. dashed).
 */
public record CostForecastPointDTO(
        Date date,
        BigDecimal cost,
        boolean isForecast
) {}
