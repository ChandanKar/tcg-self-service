package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * A month of AWS Customer Carbon Footprint emissions for one account, region and service (E37-T01),
 * in kg CO2e: market-based (MBM) and location-based (LBM) when the export has them.
 */
@Entity
@Table(name = "carbon_monthly")
public class CarbonMonthly {

    @Id
    @Column(name = "carbon_id", length = 36)
    private String carbonId;

    /** The first day of the month. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    @Column(name = "account_number", nullable = false, length = 12)
    private String accountNumber;

    @Column(name = "region", nullable = false, length = 30)
    private String region;

    @Column(name = "service_code", nullable = false, length = 100)
    private String serviceCode;

    @Column(name = "emissions_mbm_kg", precision = 14, scale = 4)
    private BigDecimal emissionsMbmKg;

    @Column(name = "emissions_lbm_kg", precision = 14, scale = 4)
    private BigDecimal emissionsLbmKg;

    @Column(name = "source_file", length = 1024)
    private String sourceFile;

    @CreationTimestamp
    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Timestamp ingestedAt;

    public String getCarbonId() { return carbonId; }
    public void setCarbonId(String carbonId) { this.carbonId = carbonId; }
    public LocalDate getMonth() { return month; }
    public void setMonth(LocalDate month) { this.month = month; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public BigDecimal getEmissionsMbmKg() { return emissionsMbmKg; }
    public void setEmissionsMbmKg(BigDecimal emissionsMbmKg) { this.emissionsMbmKg = emissionsMbmKg; }
    public BigDecimal getEmissionsLbmKg() { return emissionsLbmKg; }
    public void setEmissionsLbmKg(BigDecimal emissionsLbmKg) { this.emissionsLbmKg = emissionsLbmKg; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public Timestamp getIngestedAt() { return ingestedAt; }
}
