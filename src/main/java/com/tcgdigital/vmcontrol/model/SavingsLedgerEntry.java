package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/** One VM-day of realised savings for one cause (E17-T02). */
@Entity
@Table(name = "savings_ledger_entry")
public class SavingsLedgerEntry {

    @Id
    @Column(name = "entry_id", length = 36)
    private String entryId;

    @Column(name = "ledger_date", nullable = false)
    private LocalDate ledgerDate;

    @Column(name = "vm_id", nullable = false, length = 36)
    private String vmId;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Column(name = "team", length = 100)
    private String team;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause", nullable = false, length = 20)
    private SavingsCause cause;

    @Column(name = "avoided_hours", nullable = false, precision = 8, scale = 3)
    private BigDecimal avoidedHours;

    @Column(name = "hourly_rate", precision = 10, scale = 5)
    private BigDecimal hourlyRate;

    @Column(name = "gross_saving", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossSaving;

    @Column(name = "residual_storage_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal residualStorageCost = BigDecimal.ZERO;

    @Column(name = "residual_ipv4_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal residualIpv4Cost = BigDecimal.ZERO;

    @Column(name = "commitment_coverage_percent", precision = 5, scale = 2)
    private BigDecimal commitmentCoveragePercent;

    @Column(name = "commitment_adjustment", nullable = false, precision = 12, scale = 2)
    private BigDecimal commitmentAdjustment = BigDecimal.ZERO;

    @Column(name = "net_saving", nullable = false, precision = 12, scale = 2)
    private BigDecimal netSaving;

    @Column(name = "price_approximate", nullable = false)
    private boolean priceApproximate;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Timestamp createdAt;

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public LocalDate getLedgerDate() { return ledgerDate; }
    public void setLedgerDate(LocalDate ledgerDate) { this.ledgerDate = ledgerDate; }
    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public SavingsCause getCause() { return cause; }
    public void setCause(SavingsCause cause) { this.cause = cause; }
    public BigDecimal getAvoidedHours() { return avoidedHours; }
    public void setAvoidedHours(BigDecimal avoidedHours) { this.avoidedHours = avoidedHours; }
    public BigDecimal getHourlyRate() { return hourlyRate; }
    public void setHourlyRate(BigDecimal hourlyRate) { this.hourlyRate = hourlyRate; }
    public BigDecimal getGrossSaving() { return grossSaving; }
    public void setGrossSaving(BigDecimal grossSaving) { this.grossSaving = grossSaving; }
    public BigDecimal getResidualStorageCost() { return residualStorageCost; }
    public void setResidualStorageCost(BigDecimal v) { this.residualStorageCost = v; }
    public BigDecimal getResidualIpv4Cost() { return residualIpv4Cost; }
    public void setResidualIpv4Cost(BigDecimal v) { this.residualIpv4Cost = v; }
    public BigDecimal getCommitmentCoveragePercent() { return commitmentCoveragePercent; }
    public void setCommitmentCoveragePercent(BigDecimal v) { this.commitmentCoveragePercent = v; }
    public BigDecimal getCommitmentAdjustment() { return commitmentAdjustment; }
    public void setCommitmentAdjustment(BigDecimal v) { this.commitmentAdjustment = v; }
    public BigDecimal getNetSaving() { return netSaving; }
    public void setNetSaving(BigDecimal netSaving) { this.netSaving = netSaving; }
    public boolean isPriceApproximate() { return priceApproximate; }
    public void setPriceApproximate(boolean priceApproximate) { this.priceApproximate = priceApproximate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Timestamp getCreatedAt() { return createdAt; }
}
