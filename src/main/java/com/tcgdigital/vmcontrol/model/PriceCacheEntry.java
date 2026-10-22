package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/** An on-demand price read from the AWS Price List API (U19-T05); a null price means "not sold there". */
@Entity
@Table(name = "price_cache")
public class PriceCacheEntry {

    @Id
    @Column(name = "price_cache_id", length = 36)
    private String priceCacheId;

    @Column(name = "service_code", nullable = false, length = 32)
    private String serviceCode;

    @Column(name = "region_code", nullable = false, length = 32)
    private String regionCode;

    @Column(name = "price_key", nullable = false, length = 128)
    private String priceKey;

    @Column(name = "unit_price", precision = 14, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "fetched_at", nullable = false)
    private Timestamp fetchedAt;

    @PrePersist
    protected void onCreate() {
        if (priceCacheId == null) priceCacheId = UUID.randomUUID().toString();
    }

    public String getPriceCacheId() { return priceCacheId; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getPriceKey() { return priceKey; }
    public void setPriceKey(String priceKey) { this.priceKey = priceKey; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Timestamp getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Timestamp fetchedAt) { this.fetchedAt = fetchedAt; }
}
