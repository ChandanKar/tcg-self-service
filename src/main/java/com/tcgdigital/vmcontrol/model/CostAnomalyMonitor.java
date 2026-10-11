package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/** An AWS Cost Anomaly Detection monitor and the environment it watches (E21-T01). */
@Entity
@Table(name = "cost_anomaly_monitor")
public class CostAnomalyMonitor {

    @Id
    @Column(name = "monitor_arn", length = 255)
    private String monitorArn;

    /** The watched environment; null when not (yet) linked. */
    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "monitor_name", nullable = false, length = 255)
    private String monitorName;

    /** DIMENSIONAL or CUSTOM. */
    @Column(name = "monitor_type", length = 20)
    private String monitorType;

    /** Created by the app (cost.anomalies.manage-monitors). */
    @Column(name = "managed", nullable = false)
    private Boolean managed = false;

    /** Linked by an admin; a sync never re-links it. */
    @Column(name = "linked_manually", nullable = false)
    private Boolean linkedManually = false;

    @Column(name = "last_seen_at")
    private Timestamp lastSeenAt;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    public CostAnomalyMonitor() {
    }

    public String getMonitorArn() {
        return monitorArn;
    }

    public void setMonitorArn(String monitorArn) {
        this.monitorArn = monitorArn;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getMonitorName() {
        return monitorName;
    }

    public void setMonitorName(String monitorName) {
        this.monitorName = monitorName;
    }

    public String getMonitorType() {
        return monitorType;
    }

    public void setMonitorType(String monitorType) {
        this.monitorType = monitorType;
    }

    public Boolean getManaged() {
        return managed;
    }

    public void setManaged(Boolean managed) {
        this.managed = managed;
    }

    public Boolean getLinkedManually() {
        return linkedManually;
    }

    public void setLinkedManually(Boolean linkedManually) {
        this.linkedManually = linkedManually;
    }

    public Timestamp getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Timestamp lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }
}
