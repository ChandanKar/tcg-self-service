package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "vm_idle_summary")
public class VmIdleSummary {

    @Id
    @Column(name = "idle_summary_id", length = 36)
    private String idleSummaryId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Column(name = "is_idle", nullable = false)
    private Boolean idle = false;

    @Column(name = "idle_since")
    private Timestamp idleSince;

    @Column(name = "idle_duration_minutes", nullable = false)
    private Integer idleDurationMinutes = 0;

    @Column(name = "latest_cpu_utilization", precision = 7, scale = 3)
    private BigDecimal latestCpuUtilization;

    @Column(name = "latest_network_in_bytes")
    private Long latestNetworkInBytes;

    @Column(name = "latest_network_out_bytes")
    private Long latestNetworkOutBytes;

    @Column(name = "latest_disk_read_bytes")
    private Long latestDiskReadBytes;

    @Column(name = "latest_disk_write_bytes")
    private Long latestDiskWriteBytes;

    @Column(name = "latest_sample_time")
    private Timestamp latestSampleTime;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        if (idleSummaryId == null) {
            idleSummaryId = UUID.randomUUID().toString();
        }
        if (updatedAt == null) {
            updatedAt = new Timestamp(System.currentTimeMillis());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public String getIdleSummaryId() { return idleSummaryId; }
    public void setIdleSummaryId(String idleSummaryId) { this.idleSummaryId = idleSummaryId; }
    public Vm getVm() { return vm; }
    public void setVm(Vm vm) { this.vm = vm; }
    public Boolean getIdle() { return idle; }
    public void setIdle(Boolean idle) { this.idle = idle; }
    public Timestamp getIdleSince() { return idleSince; }
    public void setIdleSince(Timestamp idleSince) { this.idleSince = idleSince; }
    public Integer getIdleDurationMinutes() { return idleDurationMinutes; }
    public void setIdleDurationMinutes(Integer idleDurationMinutes) { this.idleDurationMinutes = idleDurationMinutes; }
    public BigDecimal getLatestCpuUtilization() { return latestCpuUtilization; }
    public void setLatestCpuUtilization(BigDecimal latestCpuUtilization) { this.latestCpuUtilization = latestCpuUtilization; }
    public Long getLatestNetworkInBytes() { return latestNetworkInBytes; }
    public void setLatestNetworkInBytes(Long latestNetworkInBytes) { this.latestNetworkInBytes = latestNetworkInBytes; }
    public Long getLatestNetworkOutBytes() { return latestNetworkOutBytes; }
    public void setLatestNetworkOutBytes(Long latestNetworkOutBytes) { this.latestNetworkOutBytes = latestNetworkOutBytes; }
    public Long getLatestDiskReadBytes() { return latestDiskReadBytes; }
    public void setLatestDiskReadBytes(Long latestDiskReadBytes) { this.latestDiskReadBytes = latestDiskReadBytes; }
    public Long getLatestDiskWriteBytes() { return latestDiskWriteBytes; }
    public void setLatestDiskWriteBytes(Long latestDiskWriteBytes) { this.latestDiskWriteBytes = latestDiskWriteBytes; }
    public Timestamp getLatestSampleTime() { return latestSampleTime; }
    public void setLatestSampleTime(Timestamp latestSampleTime) { this.latestSampleTime = latestSampleTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
