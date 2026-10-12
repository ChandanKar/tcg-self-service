package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "vm_metric_daily")
public class VmMetricDaily {
    @Id
    @Column(name = "metric_daily_id", length = 36)
    private String metricDailyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Column(name = "bucket_date", nullable = false)
    private Date bucketDate;

    @Column(name = "avg_cpu_utilization", precision = 7, scale = 3)
    private BigDecimal avgCpuUtilization;

    @Column(name = "max_cpu_utilization", precision = 7, scale = 3)
    private BigDecimal maxCpuUtilization;

    @Column(name = "sum_network_in_bytes")
    private Long sumNetworkInBytes;

    @Column(name = "sum_network_out_bytes")
    private Long sumNetworkOutBytes;

    @Column(name = "sum_disk_read_bytes")
    private Long sumDiskReadBytes;

    @Column(name = "sum_disk_write_bytes")
    private Long sumDiskWriteBytes;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    /** Daily memory utilisation; null when the CloudWatch agent reported none (E30-T01). */
    @Column(name = "avg_memory_utilization", precision = 7, scale = 3)
    private BigDecimal avgMemoryUtilization;

    @Column(name = "max_memory_utilization", precision = 7, scale = 3)
    private BigDecimal maxMemoryUtilization;

    /** Samples that carried a memory value; 0 for a VM without the agent. */
    @Column(name = "memory_sample_count", nullable = false)
    private Integer memorySampleCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        if (metricDailyId == null) metricDailyId = UUID.randomUUID().toString();
    }

    public String getMetricDailyId() { return metricDailyId; }
    public void setMetricDailyId(String metricDailyId) { this.metricDailyId = metricDailyId; }
    public Vm getVm() { return vm; }
    public void setVm(Vm vm) { this.vm = vm; }
    public Date getBucketDate() { return bucketDate; }
    public void setBucketDate(Date bucketDate) { this.bucketDate = bucketDate; }
    public BigDecimal getAvgCpuUtilization() { return avgCpuUtilization; }
    public void setAvgCpuUtilization(BigDecimal avgCpuUtilization) { this.avgCpuUtilization = avgCpuUtilization; }
    public BigDecimal getMaxCpuUtilization() { return maxCpuUtilization; }
    public void setMaxCpuUtilization(BigDecimal maxCpuUtilization) { this.maxCpuUtilization = maxCpuUtilization; }
    public Long getSumNetworkInBytes() { return sumNetworkInBytes; }
    public void setSumNetworkInBytes(Long sumNetworkInBytes) { this.sumNetworkInBytes = sumNetworkInBytes; }
    public Long getSumNetworkOutBytes() { return sumNetworkOutBytes; }
    public void setSumNetworkOutBytes(Long sumNetworkOutBytes) { this.sumNetworkOutBytes = sumNetworkOutBytes; }
    public Long getSumDiskReadBytes() { return sumDiskReadBytes; }
    public void setSumDiskReadBytes(Long sumDiskReadBytes) { this.sumDiskReadBytes = sumDiskReadBytes; }
    public Long getSumDiskWriteBytes() { return sumDiskWriteBytes; }
    public void setSumDiskWriteBytes(Long sumDiskWriteBytes) { this.sumDiskWriteBytes = sumDiskWriteBytes; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public BigDecimal getAvgMemoryUtilization() { return avgMemoryUtilization; }
    public void setAvgMemoryUtilization(BigDecimal avgMemoryUtilization) { this.avgMemoryUtilization = avgMemoryUtilization; }
    public BigDecimal getMaxMemoryUtilization() { return maxMemoryUtilization; }
    public void setMaxMemoryUtilization(BigDecimal maxMemoryUtilization) { this.maxMemoryUtilization = maxMemoryUtilization; }
    public Integer getMemorySampleCount() { return memorySampleCount; }
    public void setMemorySampleCount(Integer memorySampleCount) { this.memorySampleCount = memorySampleCount; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
