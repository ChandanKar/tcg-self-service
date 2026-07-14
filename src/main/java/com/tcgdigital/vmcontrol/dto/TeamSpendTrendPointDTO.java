package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Date;

/**
 * One team's spend on one day. Flat (team, date, cost) triples — the frontend pivots these into
 * one stacked line series per team, rather than the API shaping a nested per-team structure.
 */
public record TeamSpendTrendPointDTO(
        Date date,
        String team,
        BigDecimal estimatedCost
) {}
