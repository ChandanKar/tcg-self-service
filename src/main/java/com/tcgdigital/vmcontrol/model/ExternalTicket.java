package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/** A ticket in an outside tracker for a recommendation or anomaly (E24-T06). */
@Entity
@Table(name = "external_ticket")
public class ExternalTicket {

    public enum SourceType { RIGHTSIZING, IDLE, ANOMALY }

    @Id
    @Column(name = "ticket_id", length = 36)
    private String ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    /** "JIRA". */
    @Column(name = "tracker_system", nullable = false, length = 20)
    private String system;

    @Column(name = "issue_key", nullable = false, length = 50)
    private String issueKey;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    public ExternalTicket() {
    }

    public ExternalTicket(String ticketId, SourceType sourceType, String sourceId, String system, String issueKey, String url,
                          String environmentId, String createdBy, Timestamp createdAt) {
        this.ticketId = ticketId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.system = system;
        this.issueKey = issueKey;
        this.url = url;
        this.environmentId = environmentId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getTicketId() { return ticketId; }
    public SourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public String getSystem() { return system; }
    public String getIssueKey() { return issueKey; }
    public String getUrl() { return url; }
    public String getEnvironmentId() { return environmentId; }
    public String getCreatedBy() { return createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
}
