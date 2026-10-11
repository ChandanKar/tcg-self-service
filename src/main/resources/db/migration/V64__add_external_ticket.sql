-- E24-T06: tickets created in outside trackers (Jira) for a recommendation or an anomaly. One
-- per source and system, so asking again returns the same issue.

CREATE TABLE external_ticket (
    ticket_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_type VARCHAR(20) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    tracker_system VARCHAR(20) NOT NULL,
    issue_key VARCHAR(50) NOT NULL,
    url VARCHAR(500) NOT NULL,
    environment_id VARCHAR(36) NULL,
    created_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_external_ticket_source UNIQUE (source_type, source_id, tracker_system)
);
