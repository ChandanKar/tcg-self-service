package com.tcgdigital.vmcontrol.dto;

import java.time.Instant;
import java.util.List;

/**
 * A month's chargeback statement (E23-T06): rows by total descending, a totals row, and where the
 * figures came from.
 */
public record ChargebackDTO(
        String month,
        List<ChargebackRowDTO> rows,
        ChargebackRowDTO totals,
        /** e.g. "AWS billing for 80.0% of environment-days; estimates for the rest". */
        String dataSource,
        Instant generatedAt) {
}
