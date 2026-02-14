package com.tcgdigital.vmcontrol.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VmGroup - logical collection of VMs within an environment.
 * Groups have sequence positions and dependencies for orchestration.
 */
@Entity
@Table(name = "vm_group")
public class VmGroup {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Id
    @Column(name = "group_id", length = 36)
    private String groupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sequence_position", nullable = false)
    private Integer sequencePosition;

    @Column(name = "depends_on_group_ids", columnDefinition = "TEXT")
    private String dependsOnGroupIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Vm> vms = new ArrayList<>();

    public VmGroup() {
    }

    public VmGroup(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Helper method to get dependencies as a list.
     */
    @Transient
    public List<String> getDependencies() {
        if (dependsOnGroupIds == null || dependsOnGroupIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(dependsOnGroupIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Helper method to set dependencies from a list.
     */
    public void setDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            this.dependsOnGroupIds = null;
            return;
        }
        try {
            this.dependsOnGroupIds = objectMapper.writeValueAsString(dependencies);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize dependencies", e);
        }
    }

    // Getters and Setters
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
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

    public String getDependsOnGroupIds() {
        return dependsOnGroupIds;
    }

    public void setDependsOnGroupIds(String dependsOnGroupIds) {
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public List<Vm> getVms() {
        return vms;
    }

    public void setVms(List<Vm> vms) {
        this.vms = vms;
    }
}

