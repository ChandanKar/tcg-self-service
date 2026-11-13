package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The fleet's month so far, one definition for every screen (U23-T01): the daily snapshots from the
 * 1st to yesterday (AWS billing where an environment-day has it, else the estimate) plus today's
 * live estimate; and the month-end forecast, month to date plus the average of the last 7 complete
 * days for each day left. Home, Cost management and My account all show these figures.
 *
 * @param completeThrough the last complete day counted from snapshots (yesterday)
 * @param completeDays    the snapshots' part of the month to date
 * @param todaySoFar      today's live estimate, midnight to now
 * @param billedDays      complete days this month whose every row has an AWS billing figure
 * @param estimatedDays   complete days this month with at least one estimated row
 * @param firstDay        the first day of the month with a snapshot; null when there is none yet
 * @param dailyAverage    the average of the averaged days; null without any
 * @param averagedDays    how many of the last 7 complete days had data
 * @param daysLeft        whole days after today in the month
 * @param forecast        month to date + dailyAverage x daysLeft; null without any day to average
 */
public record FleetMonthDTO(
        LocalDate monthStart,
        LocalDate completeThrough,
        BigDecimal monthToDate,
        BigDecimal completeDays,
        BigDecimal todaySoFar,
        int billedDays,
        int estimatedDays,
        Integer firstDay,
        BigDecimal dailyAverage,
        int averagedDays,
        int daysLeft,
        BigDecimal forecast) {
}
