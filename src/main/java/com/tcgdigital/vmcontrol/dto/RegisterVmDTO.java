package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO for registering a new VM.
 */
public class RegisterVmDTO {

    @NotBlank(message = "Group ID is required")
    private String groupId;

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    private String name;

    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 255, message = "Display name must be between 2 and 255 characters")
    private String displayName;

    private String description;

    @NotNull(message = "Provider is required")
    private CloudProvider provider;

    @NotBlank(message = "Region is required")
    private String region;

    @NotBlank(message = "Provider VM ID is required")
    private String providerVmId;

    private VmType vmType = VmType.DEV;

    @NotNull(message = "Sequence position is required")
    @Min(value = 1, message = "Sequence position must be at least 1")
    private Integer sequencePosition;

    private List<String> dependsOnVmIds;

    private String metadata;

    public RegisterVmDTO() {
    }

    // Getters and Setters
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}

