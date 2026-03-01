package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.Environment;

import java.sql.Timestamp;

/**
 * DTO for Environment entity.
 */
public class EnvironmentDTO {

    private String environmentId;
    private String name;
    private String displayName;
    private String description;
    private Boolean isActive;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private int groupCount;
    private int vmCount;
    private String metadata;

    public EnvironmentDTO() {
    }

    public static EnvironmentDTO fromEntity(Environment environment) {
        EnvironmentDTO dto = new EnvironmentDTO();
        dto.setEnvironmentId(environment.getEnvironmentId());
        dto.setName(environment.getName());
        dto.setDisplayName(environment.getDisplayName());
        dto.setDescription(environment.getDescription());
        dto.setIsActive(environment.getIsActive());
        dto.setCreatedAt(environment.getCreatedAt());
        dto.setUpdatedAt(environment.getUpdatedAt());
        dto.setMetadata(environment.getMetadata());
        return dto;
    }

    public static EnvironmentDTO fromEntityWithCounts(Environment environment, int groupCount, int vmCount) {
        EnvironmentDTO dto = fromEntity(environment);
        dto.setGroupCount(groupCount);
        dto.setVmCount(vmCount);
        return dto;
    }

    // Getters and Setters
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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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

    public int getGroupCount() {
        return groupCount;
    }

    public void setGroupCount(int groupCount) {
        this.groupCount = groupCount;
    }

    public int getVmCount() {
        return vmCount;
    }

    public void setVmCount(int vmCount) {
        this.vmCount = vmCount;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}

