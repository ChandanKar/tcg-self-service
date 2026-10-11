package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** How a shared environment's cost is split between teams (E23-T05). One rule per environment. */
@Entity
@Table(name = "cost_split_rule")
public class CostSplitRule {

    public enum Method {
        /** The same amount for each team. */
        EVEN,
        /** Each team's fixed percentage; the percentages sum to 100. */
        FIXED_PERCENT,
        /** In proportion to each team's direct cost that month (even when all are zero). */
        PROPORTIONAL_DIRECT
    }

    @Id
    @Column(name = "rule_id", length = 36)
    private String ruleId;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private Method method;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cost_split_share", joinColumns = @JoinColumn(name = "rule_id"))
    @OrderColumn(name = "position")
    private List<CostSplitShare> shares = new ArrayList<>();

    public CostSplitRule() {
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public Method getMethod() { return method; }
    public void setMethod(Method method) { this.method = method; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public List<CostSplitShare> getShares() { return shares; }

    /** Replaces the shares in place (the collection is managed by Hibernate). */
    public void setShares(List<CostSplitShare> shares) {
        this.shares.clear();
        this.shares.addAll(shares);
    }
}
