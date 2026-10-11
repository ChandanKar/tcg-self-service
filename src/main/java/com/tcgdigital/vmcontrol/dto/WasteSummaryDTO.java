package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.WasteFindingType;

import java.math.BigDecimal;
import java.util.List;

/** Open waste per type and in total, estimated USD per month (E22-T03). */
public record WasteSummaryDTO(boolean sweepEnabled, int openCount, BigDecimal monthlyTotal, List<TypeTotal> byType) {

    public record TypeTotal(WasteFindingType type, long count, BigDecimal monthly) {}
}
