package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

/**
 * The 18-month view (E35-T02): AWS's latest monthly forecast for platform-managed resources and,
 * for comparison, the platform's run-rate for each of those months (the last 30 days' average daily
 * estimated cost times the month's days).
 */
public record LongRangeForecastDTO(Date awsGeneratedOn, List<AwsForecastPointDTO> awsMonthly, List<RunRate> platformRunRate,
                                   BigDecimal averageDailyCost) {

    /** The platform's run-rate for one month. */
    public record RunRate(Date periodStart, BigDecimal amount) {
    }
}
