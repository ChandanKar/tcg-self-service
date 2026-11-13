package com.tcgdigital.vmcontrol.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A month's chargeback statement (E23-T06): rows by total descending, a totals row, where the
 * figures came from, and whether it is frozen (E23-T07).
 */
public record ChargebackDTO(
        String month,
        List<ChargebackRowDTO> rows,
        ChargebackRowDTO totals,
        /** e.g. "AWS billing for 80.0% of environment-days; estimates for the rest". */
        String dataSource,
        Instant generatedAt,
        /** DRAFT for a live computation, FINAL for a frozen statement (E23-T07). */
        String status,
        /** Set only on a "finalise and send" answer (U15-T09): whether finance was emailed. */
        Boolean emailed,
        /**
         * The first and last day with a snapshot in the month (U23-T02), so the statement can say which
         * days it covers ("1 to 12 Nov"); null without data, and on statements finalised before U23.
         */
        LocalDate dataFrom,
        LocalDate dataThrough) {
    public ChargebackDTO(String month, List<ChargebackRowDTO> rows, ChargebackRowDTO totals, String dataSource,
                         Instant generatedAt, String status) {
        this(month, rows, totals, dataSource, generatedAt, status, null, null, null);
    }

    public ChargebackDTO withStatus(String newStatus) {
        return new ChargebackDTO(month, rows, totals, dataSource, generatedAt, newStatus, emailed, dataFrom, dataThrough);
    }

    public ChargebackDTO withEmailed(boolean sent) {
        return new ChargebackDTO(month, rows, totals, dataSource, generatedAt, status, sent, dataFrom, dataThrough);
    }

    public ChargebackDTO withDays(LocalDate from, LocalDate through) {
        return new ChargebackDTO(month, rows, totals, dataSource, generatedAt, status, emailed, from, through);
    }
}
