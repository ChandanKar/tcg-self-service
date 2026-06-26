package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "vm_inventory_snapshot")
public class VmInventorySnapshot {

    @Id
    @Column(name = "inventory_id", length = 36)
    private String inventoryId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private CloudProvider provider;

    @Column(name = "provider_vm_id", nullable = false)
    private String providerVmId;

    @Column(name = "instance_type", length = 64)
    private String instanceType;

    @Column(name = "vcpu_count")
    private Integer vcpuCount;

    @Column(name = "memory_mib")
    private Integer memoryMib;

    @Column(name = "architecture", length = 64)
    private String architecture;

    @Column(name = "private_ip", length = 64)
    private String privateIp;

    @Column(name = "public_ip", length = 64)
    private String publicIp;

    @Column(name = "availability_zone", length = 64)
    private String availabilityZone;

    @Column(name = "launch_time")
    private Timestamp launchTime;

    @Column(name = "total_storage_gib")
    private Integer totalStorageGib;

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
        if (inventoryId == null) {
            inventoryId = UUID.randomUUID().toString();
        }
    }

    public String getInventoryId() { return inventoryId; }
    public void setInventoryId(String inventoryId) { this.inventoryId = inventoryId; }
    public Vm getVm() { return vm; }
    public void setVm(Vm vm) { this.vm = vm; }
    public CloudProvider getProvider() { return provider; }
    public void setProvider(CloudProvider provider) { this.provider = provider; }
    public String getProviderVmId() { return providerVmId; }
    public void setProviderVmId(String providerVmId) { this.providerVmId = providerVmId; }
    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }
    public Integer getVcpuCount() { return vcpuCount; }
    public void setVcpuCount(Integer vcpuCount) { this.vcpuCount = vcpuCount; }
    public Integer getMemoryMib() { return memoryMib; }
    public void setMemoryMib(Integer memoryMib) { this.memoryMib = memoryMib; }
    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }
    public String getPrivateIp() { return privateIp; }
    public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }
    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }
    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
    public Timestamp getLaunchTime() { return launchTime; }
    public void setLaunchTime(Timestamp launchTime) { this.launchTime = launchTime; }
    public Integer getTotalStorageGib() { return totalStorageGib; }
    public void setTotalStorageGib(Integer totalStorageGib) { this.totalStorageGib = totalStorageGib; }
    public Timestamp getLastRefreshedAt() { return lastRefreshedAt; }
    public void setLastRefreshedAt(Timestamp lastRefreshedAt) { this.lastRefreshedAt = lastRefreshedAt; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
