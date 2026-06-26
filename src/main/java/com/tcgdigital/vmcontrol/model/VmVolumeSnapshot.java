package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "vm_volume_snapshot")
public class VmVolumeSnapshot {

    @Id
    @Column(name = "volume_snapshot_id", length = 36)
    private String volumeSnapshotId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Column(name = "volume_id", nullable = false, length = 128)
    private String volumeId;

    @Column(name = "device_name", length = 128)
    private String deviceName;

    @Column(name = "volume_type", length = 32)
    private String volumeType;

    @Column(name = "size_gib")
    private Integer sizeGib;

    @Column(name = "iops")
    private Integer iops;

    @Column(name = "throughput_mbps")
    private Integer throughputMbps;

    @Column(name = "encrypted")
    private Boolean encrypted;

    @Column(name = "delete_on_termination")
    private Boolean deleteOnTermination;

    @Column(name = "last_refreshed_at", nullable = false)
    private Timestamp lastRefreshedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        if (volumeSnapshotId == null) {
            volumeSnapshotId = UUID.randomUUID().toString();
        }
    }

    public String getVolumeSnapshotId() { return volumeSnapshotId; }
    public void setVolumeSnapshotId(String volumeSnapshotId) { this.volumeSnapshotId = volumeSnapshotId; }
    public Vm getVm() { return vm; }
    public void setVm(Vm vm) { this.vm = vm; }
    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getVolumeType() { return volumeType; }
    public void setVolumeType(String volumeType) { this.volumeType = volumeType; }
    public Integer getSizeGib() { return sizeGib; }
    public void setSizeGib(Integer sizeGib) { this.sizeGib = sizeGib; }
    public Integer getIops() { return iops; }
    public void setIops(Integer iops) { this.iops = iops; }
    public Integer getThroughputMbps() { return throughputMbps; }
    public void setThroughputMbps(Integer throughputMbps) { this.throughputMbps = throughputMbps; }
    public Boolean getEncrypted() { return encrypted; }
    public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }
    public Boolean getDeleteOnTermination() { return deleteOnTermination; }
    public void setDeleteOnTermination(Boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }
    public Timestamp getLastRefreshedAt() { return lastRefreshedAt; }
    public void setLastRefreshedAt(Timestamp lastRefreshedAt) { this.lastRefreshedAt = lastRefreshedAt; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
