package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/** One team's share of a shared environment's cost (E23-T05); percent only for FIXED_PERCENT. */
@Embeddable
public class CostSplitShare {

    @Column(name = "team_id", nullable = false, length = 36)
    private String teamId;

    @Column(name = "percent", precision = 5, scale = 2)
    private BigDecimal percent;

    public CostSplitShare() {
    }

    public CostSplitShare(String teamId, BigDecimal percent) {
        this.teamId = teamId;
        this.percent = percent;
    }

    public String getTeamId() { return teamId; }
    public BigDecimal getPercent() { return percent; }
}
