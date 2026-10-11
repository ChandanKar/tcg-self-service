package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** A shared-cost split rule with environment and team names (E23-T05). */
public record CostSplitRuleDTO(
        String ruleId,
        String environmentId,
        String environmentName,
        String method,
        LocalDate effectiveFrom,
        List<Share> shares,
        String createdBy,
        Timestamp createdAt,
        Timestamp updatedAt) {

    public record Share(String teamId, String teamName, BigDecimal percent) {}

    /** Create/update body; percent is only read for FIXED_PERCENT. effectiveFrom defaults to this month. */
    public record Request(String environmentId, String method, LocalDate effectiveFrom, List<ShareRequest> shares) {}

    public record ShareRequest(String teamId, BigDecimal percent) {}
}
