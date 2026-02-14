package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO for creating a new VmGroup.
 */
public class CreateVmGroupDTO {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    private String name;

    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 255, message = "Display name must be between 2 and 255 characters")
    private String displayName;

    private String description;

    @NotNull(message = "Sequence position is required")
    @Min(value = 1, message = "Sequence position must be at least 1")
    private Integer sequencePosition;

    private List<String> dependsOnGroupIds;

    private String metadata;

    public CreateVmGroupDTO() {
    }

    // Getters and Setters
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}

