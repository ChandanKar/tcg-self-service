package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/**
 * @param points historical points followed by forecast points, in date order — empty when
 *               {@code sufficientHistory} is false.
 * @param sufficientHistory false when there's fewer than {@code minHistoryDaysRequired} days of
 *                          snapshot history — the forecast refuses to extrapolate from too little
 *                          data rather than draw a misleading trend line from noise.
 */
public record CostForecastDTO(
        List<CostForecastPointDTO> points,
        boolean sufficientHistory,
        int historyDaysUsed,
        int minHistoryDaysRequired
) {}
