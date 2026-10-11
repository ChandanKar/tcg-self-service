-- E19-T03: per-rule overrides. An override skips a rule's START, STOP or ALL actions whose due
-- time falls in [starts_at, ends_at), e.g. "keep running tonight for a release", without
-- disabling the rule (and forgetting to re-enable it).

CREATE TABLE automation_rule_override (
    override_id VARCHAR(36) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    starts_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    skip_action VARCHAR(10) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_by_user_id VARCHAR(36) NOT NULL,
    INDEX idx_automation_rule_override_rule_end (rule_id, ends_at),
    CONSTRAINT fk_automation_rule_override_rule FOREIGN KEY (rule_id)
        REFERENCES automation_rule (rule_id) ON DELETE CASCADE
);
