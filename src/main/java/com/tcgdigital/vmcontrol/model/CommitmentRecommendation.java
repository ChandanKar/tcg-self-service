package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;

/**
 * One AWS purchase recommendation summary (E27-T01): a Savings Plan type or EC2 Reserved
 * Instances, for a term and payment option, as Cost Explorer generated it on {@code generatedOn}.
 * {@code details} holds the top lines as JSON. Display only.
 */
@Entity
@Table(name = "commitment_recommendation")
public class CommitmentRecommendation {

    public enum Type { COMPUTE_SP, EC2_INSTANCE_SP, EC2_RI }

    @Id
    @Column(name = "rec_id", length = 36)
    private String recId;

    @Column(name = "generated_on", nullable = false)
    private Date generatedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "rec_type", nullable = false, length = 30)
    private Type recType;

    @Column(name = "term", nullable = false, length = 10)
    private String term;

    @Column(name = "payment_option", nullable = false, length = 20)
    private String paymentOption;

    @Column(name = "lookback_days")
    private Integer lookbackDays;

    @Column(name = "hourly_commitment", precision = 12, scale = 4)
    private BigDecimal hourlyCommitment;

    @Column(name = "upfront_cost", precision = 12, scale = 2)
    private BigDecimal upfrontCost;

    @Column(name = "estimated_monthly_savings", precision = 12, scale = 2)
    private BigDecimal estimatedMonthlySavings;

    @Column(name = "estimated_savings_percent", precision = 6, scale = 2)
    private BigDecimal estimatedSavingsPercent;

    @Column(name = "estimated_on_demand_cost", precision = 12, scale = 2)
    private BigDecimal estimatedOnDemandCost;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Timestamp createdAt;

    public String getRecId() { return recId; }
    public void setRecId(String recId) { this.recId = recId; }
    public Date getGeneratedOn() { return generatedOn; }
    public void setGeneratedOn(Date generatedOn) { this.generatedOn = generatedOn; }
    public Type getRecType() { return recType; }
    public void setRecType(Type recType) { this.recType = recType; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public String getPaymentOption() { return paymentOption; }
    public void setPaymentOption(String paymentOption) { this.paymentOption = paymentOption; }
    public Integer getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(Integer lookbackDays) { this.lookbackDays = lookbackDays; }
    public BigDecimal getHourlyCommitment() { return hourlyCommitment; }
    public void setHourlyCommitment(BigDecimal hourlyCommitment) { this.hourlyCommitment = hourlyCommitment; }
    public BigDecimal getUpfrontCost() { return upfrontCost; }
    public void setUpfrontCost(BigDecimal upfrontCost) { this.upfrontCost = upfrontCost; }
    public BigDecimal getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
    public void setEstimatedMonthlySavings(BigDecimal estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }
    public BigDecimal getEstimatedSavingsPercent() { return estimatedSavingsPercent; }
    public void setEstimatedSavingsPercent(BigDecimal estimatedSavingsPercent) { this.estimatedSavingsPercent = estimatedSavingsPercent; }
    public BigDecimal getEstimatedOnDemandCost() { return estimatedOnDemandCost; }
    public void setEstimatedOnDemandCost(BigDecimal estimatedOnDemandCost) { this.estimatedOnDemandCost = estimatedOnDemandCost; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public Timestamp getCreatedAt() { return createdAt; }
}
