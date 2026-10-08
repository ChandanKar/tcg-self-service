package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Pattern;
import com.tcgdigital.vmcontrol.service.support.NameSanitizer;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for creating a new Environment.
 */
public class CreateEnvironmentDTO {

    @JsonProperty("name")
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    @Pattern(regexp = NameSanitizer.SAFE_NAME_REGEX, message = NameSanitizer.SAFE_NAME_MESSAGE)
    private String name;

    @JsonProperty("displayName")
    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 255, message = "Display name must be between 2 and 255 characters")
    @Pattern(regexp = NameSanitizer.SAFE_NAME_REGEX, message = NameSanitizer.SAFE_NAME_MESSAGE)
    private String displayName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("metadata")
    private String metadata;

    @JsonProperty("serviceType")
    private String serviceType = "EC2";

    /** EKS only: the exact cluster name (case kept); defaults to the name as typed. */
    @JsonProperty("eksClusterName")
    @jakarta.validation.constraints.Size(max = 255, message = "Cluster name must be at most 255 characters")
    private String eksClusterName;

    public CreateEnvironmentDTO() {
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getEksClusterName() {
        return eksClusterName;
    }

    public void setEksClusterName(String eksClusterName) {
        this.eksClusterName = eksClusterName;
    }
}
