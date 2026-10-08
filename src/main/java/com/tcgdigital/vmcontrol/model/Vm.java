package com.tcgdigital.vmcontrol.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

/**
 * Vm - individual virtual machine linked to a group.
 * VMs have sequence positions and intra-group dependencies.
 */
@Entity
@Table(name = "vm")
public class Vm {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Id
    @Column(name = "vm_id", length = 36)
    private String vmId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private VmGroup group;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 255)
    private String purpose;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CloudProvider provider;

    @Column(nullable = false)
    private String region;

    @Column(name = "provider_vm_id", nullable = false)
    private String providerVmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vm_type", length = 20, nullable = false)
    private VmType vmType = VmType.DEV;

    @Column(name = "sequence_position", nullable = false)
    private Integer sequencePosition;

    @Column(name = "depends_on_vm_ids", columnDefinition = "TEXT")
    private String dependsOnVmIds;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private VmStatus status = VmStatus.UNKNOWN;

    @Column(name = "last_known_state", length = 20)
    private String lastKnownState;

    @Column(name = "last_state_sync_at")
    private Timestamp lastStateSyncAt;

    @Column(name = "state_drift_detected", nullable = false)
    private Boolean stateDriftDetected = false;

    @Column(name = "discovery_pending", nullable = false)
    private Boolean discoveryPending = false;

    /** Consecutive NOT_FOUND results from state sync (V32); deactivation needs several (M7). */
    @Column(name = "not_found_count", nullable = false)
    private Integer notFoundCount = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    /**
     * Optimistic lock (V29). Null until first persisted, so Spring Data treats a new VM as new;
     * the targeted status/metadata/tag updates in VmRepository bump it explicitly.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "tags_synced_at")
    private Timestamp tagsSyncedAt;

    public Vm() {
    }

    public Vm(String vmId) {
        this.vmId = vmId;
    }

    /**
     * Helper method to get dependencies as a list.
     */
    @Transient
    public List<String> getDependencies() {
        if (dependsOnVmIds == null || dependsOnVmIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(dependsOnVmIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Helper method to set dependencies from a list.
     */
    public void setDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            this.dependsOnVmIds = null;
            return;
        }
        try {
            this.dependsOnVmIds = objectMapper.writeValueAsString(dependencies);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize dependencies", e);
        }
    }

    // Getters and Setters
    public String getVmId() {
        return vmId;
    }

    public void setVmId(String vmId) {
        this.vmId = vmId;
    }

    public VmGroup getGroup() {
        return group;
    }

    public void setGroup(VmGroup group) {
        this.group = group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public CloudProvider getProvider() {
        return provider;
    }

    public void setProvider(CloudProvider provider) {
        this.provider = provider;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getProviderVmId() {
        return providerVmId;
    }

    public void setProviderVmId(String providerVmId) {
        this.providerVmId = providerVmId;
    }

    public VmType getVmType() {
        return vmType;
    }

    public void setVmType(VmType vmType) {
        this.vmType = vmType;
    }

    public Integer getSequencePosition() {
        return sequencePosition;
    }

    public void setSequencePosition(Integer sequencePosition) {
        this.sequencePosition = sequencePosition;
    }

    public String getDependsOnVmIds() {
        return dependsOnVmIds;
    }

    public void setDependsOnVmIds(String dependsOnVmIds) {
        this.dependsOnVmIds = dependsOnVmIds;
    }

    public VmStatus getStatus() {
        return status;
    }

    public void setStatus(VmStatus status) {
        this.status = status;
    }

    public String getLastKnownState() {
        return lastKnownState;
    }

    public void setLastKnownState(String lastKnownState) {
        this.lastKnownState = lastKnownState;
    }

    public Timestamp getLastStateSyncAt() {
        return lastStateSyncAt;
    }

    public void setLastStateSyncAt(Timestamp lastStateSyncAt) {
        this.lastStateSyncAt = lastStateSyncAt;
    }

    public Boolean getStateDriftDetected() {
        return stateDriftDetected;
    }

    public void setStateDriftDetected(Boolean stateDriftDetected) {
        this.stateDriftDetected = stateDriftDetected;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getDiscoveryPending() {
        return discoveryPending;
    }

    public void setDiscoveryPending(Boolean discoveryPending) {
        this.discoveryPending = discoveryPending;
    }

    public Integer getNotFoundCount() {
        return notFoundCount;
    }

    public void setNotFoundCount(Integer notFoundCount) {
        this.notFoundCount = notFoundCount;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Timestamp getTagsSyncedAt() {
        return tagsSyncedAt;
    }

    public void setTagsSyncedAt(Timestamp tagsSyncedAt) {
        this.tagsSyncedAt = tagsSyncedAt;
    }

    public Long getVersion() {
        return version;
    }
}
