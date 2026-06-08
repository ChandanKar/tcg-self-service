package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.VmType;

import java.sql.Timestamp;
import java.util.List;

/**
 * DTO for Vm entity.
 */
public class VmDTO {

    private String vmId;
    private String groupId;
    private String groupName;
    private String name;
    private String displayName;
    private String description;
    private CloudProvider provider;
    private String region;
    private String providerVmId;
    private VmType vmType;
    private Integer sequencePosition;
    private List<String> dependsOnVmIds;
    private VmStatus status;
    private Timestamp lastStateSyncAt;
    private Boolean stateDriftDetected;
    private Boolean discoveryPending;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public VmDTO() {
    }

    public static VmDTO fromEntity(Vm vm) {
        VmDTO dto = new VmDTO();
        dto.setVmId(vm.getVmId());
        dto.setGroupId(vm.getGroup().getGroupId());
        dto.setGroupName(vm.getGroup().getName());
        dto.setName(vm.getName());
        dto.setDisplayName(vm.getDisplayName());
        dto.setDescription(vm.getDescription());
        dto.setProvider(vm.getProvider());
        dto.setRegion(vm.getRegion());
        dto.setProviderVmId(vm.getProviderVmId());
        dto.setVmType(vm.getVmType());
        dto.setSequencePosition(vm.getSequencePosition());
        dto.setDependsOnVmIds(vm.getDependencies());
        dto.setStatus(vm.getStatus());
        dto.setLastStateSyncAt(vm.getLastStateSyncAt());
        dto.setStateDriftDetected(vm.getStateDriftDetected());
        dto.setDiscoveryPending(vm.getDiscoveryPending());
        dto.setCreatedAt(vm.getCreatedAt());
        dto.setUpdatedAt(vm.getUpdatedAt());
        return dto;
    }

    // Getters and Setters
    public String getVmId() {
        return vmId;
    }

    public void setVmId(String vmId) {
        this.vmId = vmId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
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

    public List<String> getDependsOnVmIds() {
        return dependsOnVmIds;
    }

    public void setDependsOnVmIds(List<String> dependsOnVmIds) {
        this.dependsOnVmIds = dependsOnVmIds;
    }

    public VmStatus getStatus() {
        return status;
    }

    public void setStatus(VmStatus status) {
        this.status = status;
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

    public Boolean getDiscoveryPending() {
        return discoveryPending;
    }

    public void setDiscoveryPending(Boolean discoveryPending) {
        this.discoveryPending = discoveryPending;
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
}

