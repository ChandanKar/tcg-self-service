package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "vm_metric_sample_archive")
public class VmMetricSampleArchive {

    @Id
    @Column(name = "metric_sample_archive_id", length = 36)
    private String metricSampleArchiveId;

    @Column(name = "original_metric_sample_id", length = 36)
    private String originalMetricSampleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CloudProvider provider;

    @Column(name = "provider_vm_id", nullable = false)
    private String providerVmId;

    @Column(name = "sample_time", nullable = false)
    private Timestamp sampleTime;

    @Column(name = "period_seconds", nullable = false)
    private Integer periodSeconds;

    @Column(name = "cpu_utilization", precision = 7, scale = 3)
    private BigDecimal cpuUtilization;

    @Column(name = "network_in_bytes")
    private Long networkInBytes;

    @Column(name = "network_out_bytes")
    private Long networkOutBytes;

    @Column(name = "disk_read_bytes")
    private Long diskReadBytes;

    @Column(name = "disk_write_bytes")
    private Long diskWriteBytes;

    @Column(name = "status_at_sample", length = 32)
    private String statusAtSample;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "archived_at", nullable = false)
    private Timestamp archivedAt;

    @PrePersist
    protected void onCreate() {
        if (metricSampleArchiveId == null) metricSampleArchiveId = UUID.randomUUID().toString();
        if (archivedAt == null) archivedAt = new Timestamp(System.currentTimeMillis());
    }

    public String getMetricSampleArchiveId() { return metricSampleArchiveId; }
    public void setMetricSampleArchiveId(String metricSampleArchiveId) { this.metricSampleArchiveId = metricSampleArchiveId; }
    public String getOriginalMetricSampleId() { return originalMetricSampleId; }
    public void setOriginalMetricSampleId(String originalMetricSampleId) { this.originalMetricSampleId = originalMetricSampleId; }
    public Vm getVm() { return vm; }
    public void setVm(Vm vm) { this.vm = vm; }
    public CloudProvider getProvider() { return provider; }
    public void setProvider(CloudProvider provider) { this.provider = provider; }
    public String getProviderVmId() { return providerVmId; }
    public void setProviderVmId(String providerVmId) { this.providerVmId = providerVmId; }
    public Timestamp getSampleTime() { return sampleTime; }
    public void setSampleTime(Timestamp sampleTime) { this.sampleTime = sampleTime; }
    public Integer getPeriodSeconds() { return periodSeconds; }
    public void setPeriodSeconds(Integer periodSeconds) { this.periodSeconds = periodSeconds; }
    public BigDecimal getCpuUtilization() { return cpuUtilization; }
    public void setCpuUtilization(BigDecimal cpuUtilization) { this.cpuUtilization = cpuUtilization; }
    public Long getNetworkInBytes() { return networkInBytes; }
    public void setNetworkInBytes(Long networkInBytes) { this.networkInBytes = networkInBytes; }
    public Long getNetworkOutBytes() { return networkOutBytes; }
    public void setNetworkOutBytes(Long networkOutBytes) { this.networkOutBytes = networkOutBytes; }
    public Long getDiskReadBytes() { return diskReadBytes; }
    public void setDiskReadBytes(Long diskReadBytes) { this.diskReadBytes = diskReadBytes; }
    public Long getDiskWriteBytes() { return diskWriteBytes; }
    public void setDiskWriteBytes(Long diskWriteBytes) { this.diskWriteBytes = diskWriteBytes; }
    public String getStatusAtSample() { return statusAtSample; }
    public void setStatusAtSample(String statusAtSample) { this.statusAtSample = statusAtSample; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Timestamp archivedAt) { this.archivedAt = archivedAt; }
}
