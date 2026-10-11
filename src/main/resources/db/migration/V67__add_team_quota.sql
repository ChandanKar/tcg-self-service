-- E25-T07: monthly team quotas on running VM-hours or dollars. A start that would take the team
-- past its limit is warned about, held for approval, or blocked. team_quota_alert remembers which
-- warnings (warn_percent, 100) went out each month, so members hear each one once.

CREATE TABLE team_quota (
    quota_id VARCHAR(36) NOT NULL PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL,
    metric VARCHAR(10) NOT NULL,
    limit_value DECIMAL(14,2) NOT NULL,
    enforcement VARCHAR(20) NOT NULL,
    warn_percent INT NOT NULL DEFAULT 80,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_team_quota_metric UNIQUE (team_id, metric),
    CONSTRAINT fk_team_quota_team FOREIGN KEY (team_id) REFERENCES team (team_id) ON DELETE CASCADE
);

CREATE TABLE team_quota_alert (
    quota_id VARCHAR(36) NOT NULL,
    alert_month CHAR(7) NOT NULL,
    alert_level INT NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (quota_id, alert_month, alert_level),
    CONSTRAINT fk_team_quota_alert_quota FOREIGN KEY (quota_id) REFERENCES team_quota (quota_id) ON DELETE CASCADE
);
