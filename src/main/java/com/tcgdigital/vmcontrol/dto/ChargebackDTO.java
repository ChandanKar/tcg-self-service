package com.tcgdigital.vmcontrol.dto;

import java.time.Instant;
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
        String status) {

    public ChargebackDTO withStatus(String newStatus) {
        return new ChargebackDTO(month, rows, totals, dataSource, generatedAt, newStatus);
    }
}
