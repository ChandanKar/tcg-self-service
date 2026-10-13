package com.tcgdigital.vmcontrol.dto;

import java.sql.Date;
import java.util.List;

/**
 * @param points historical points followed by forecast points, in date order — empty when
 *               {@code sufficientHistory} is false.
 * @param sufficientHistory false when there's fewer than {@code minHistoryDaysRequired} days of
 *                          snapshot history — the forecast refuses to extrapolate from too little
 *                          data rather than draw a misleading trend line from noise.
 * @param awsDaily AWS's daily forecast for the same days (E35-T02), empty when none was captured
 * @param awsGeneratedOn when that AWS forecast was captured, or null
 */
public record CostForecastDTO(
        List<CostForecastPointDTO> points,
        boolean sufficientHistory,
        int historyDaysUsed,
        int minHistoryDaysRequired,
        List<AwsForecastPointDTO> awsDaily,
        Date awsGeneratedOn
) {
    /** Without an AWS forecast. */
    public CostForecastDTO(List<CostForecastPointDTO> points, boolean sufficientHistory, int historyDaysUsed,
                           int minHistoryDaysRequired) {
        this(points, sufficientHistory, historyDaysUsed, minHistoryDaysRequired, List.of(), null);
    }
}
