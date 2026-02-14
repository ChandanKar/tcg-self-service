package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmGroup;

import java.sql.Timestamp;
import java.util.List;

/**
 * DTO for VmGroup entity.
 */
public class VmGroupDTO {

    private String groupId;
    private String environmentId;
    private String name;
    private String displayName;
    private String description;
    private Integer sequencePosition;
    private List<String> dependsOnGroupIds;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private int vmCount;
    private int runningVmCount;

    public VmGroupDTO() {
    }

    public static VmGroupDTO fromEntity(VmGroup group) {
        VmGroupDTO dto = new VmGroupDTO();
        dto.setGroupId(group.getGroupId());
        dto.setEnvironmentId(group.getEnvironment().getEnvironmentId());
        dto.setName(group.getName());
        dto.setDisplayName(group.getDisplayName());
        dto.setDescription(group.getDescription());
        dto.setSequencePosition(group.getSequencePosition());
        dto.setDependsOnGroupIds(group.getDependencies());
        dto.setCreatedAt(group.getCreatedAt());
        dto.setUpdatedAt(group.getUpdatedAt());
        return dto;
    }

    public static VmGroupDTO fromEntityWithCounts(VmGroup group, int vmCount, int runningVmCount) {
        VmGroupDTO dto = fromEntity(group);
        dto.setVmCount(vmCount);
        dto.setRunningVmCount(runningVmCount);
        return dto;
    }

    // Getters and Setters
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
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

    public Integer getSequencePosition() {
        return sequencePosition;
    }

    public void setSequencePosition(Integer sequencePosition) {
        this.sequencePosition = sequencePosition;
    }

    public List<String> getDependsOnGroupIds() {
        return dependsOnGroupIds;
    }

    public void setDependsOnGroupIds(List<String> dependsOnGroupIds) {
        this.dependsOnGroupIds = dependsOnGroupIds;
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

    public int getVmCount() {
        return vmCount;
    }

    public void setVmCount(int vmCount) {
        this.vmCount = vmCount;
    }

    public int getRunningVmCount() {
        return runningVmCount;
    }

    public void setRunningVmCount(int runningVmCount) {
        this.runningVmCount = runningVmCount;
    }
}

