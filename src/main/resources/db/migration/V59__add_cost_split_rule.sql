-- E23-T05: shared-cost split rules. A shared environment (CI, a shared database) is charged to
-- the teams that use it: evenly, by fixed percentages (summing to 100), or in proportion to each
-- team's direct cost that month. One rule per environment.

CREATE TABLE cost_split_rule (
    rule_id VARCHAR(36) NOT NULL PRIMARY KEY,
    environment_id VARCHAR(36) NOT NULL,
    method VARCHAR(20) NOT NULL,
    effective_from DATE NOT NULL,
    created_by VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_cost_split_rule_environment UNIQUE (environment_id),
    CONSTRAINT fk_cost_split_rule_environment FOREIGN KEY (environment_id) REFERENCES environment (environment_id) ON DELETE CASCADE
);

CREATE TABLE cost_split_share (
    rule_id VARCHAR(36) NOT NULL,
    team_id VARCHAR(36) NOT NULL,
    percent DECIMAL(5,2) NULL,
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (rule_id, team_id),
    INDEX idx_cost_split_share_team (team_id),
    CONSTRAINT fk_cost_split_share_rule FOREIGN KEY (rule_id) REFERENCES cost_split_rule (rule_id) ON DELETE CASCADE,
    CONSTRAINT fk_cost_split_share_team FOREIGN KEY (team_id) REFERENCES team (team_id)
);
