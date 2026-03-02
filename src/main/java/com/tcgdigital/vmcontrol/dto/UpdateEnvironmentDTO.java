package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * DTO for updating an existing Environment.
 */
public class UpdateEnvironmentDTO {

    @JsonProperty("displayName")
    @Size(min = 2, max = 255, message = "Display name must be between 2 and 255 characters")
    private String displayName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("metadata")
    private String metadata;

    public UpdateEnvironmentDTO() {
    }

    // Getters and Setters
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
