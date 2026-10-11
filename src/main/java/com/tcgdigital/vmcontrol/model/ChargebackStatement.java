package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** A frozen monthly chargeback statement (E23-T07); payload is the ChargebackDTO as JSON. */
@Entity
@Table(name = "chargeback_statement")
public class ChargebackStatement {

    public enum Status { DRAFT, FINAL }

    @Id
    @Column(name = "statement_id", length = 36)
    private String statementId;

    /** "2026-09". */
    @Column(name = "statement_month", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String month;

    @Column(name = "generated_at", nullable = false)
    private Timestamp generatedAt;

    @Column(name = "generated_by", length = 36)
    private String generatedBy;

    @Column(name = "payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    public ChargebackStatement() {
    }

    public String getStatementId() { return statementId; }
    public void setStatementId(String statementId) { this.statementId = statementId; }
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public Timestamp getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Timestamp generatedAt) { this.generatedAt = generatedAt; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
