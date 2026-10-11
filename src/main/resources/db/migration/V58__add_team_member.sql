-- E23-T02: team membership, synced from the team's Entra group (source ENTRA) or added by an
-- admin (MANUAL). A sync only adds and removes ENTRA rows.

CREATE TABLE team_member (
    team_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    source VARCHAR(10) NOT NULL,
    synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, user_id),
    INDEX idx_team_member_user (user_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team (team_id) ON DELETE CASCADE,
    CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE
);
