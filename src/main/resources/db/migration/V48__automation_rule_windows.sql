-- E19-T01: a schedule rule has 1..7 time windows (e.g. Mon-Fri 08:00-20:00 and Sat 10:00-14:00).
-- Each window keeps its own fired-on dates. The single-window columns on automation_rule
-- (days_of_week, start_time, stop_time, last_*_fired_on) are copied here, kept for one release
-- and no longer written.

CREATE TABLE automation_rule_window (
    window_id VARCHAR(36) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    position INT NOT NULL,
    days_of_week VARCHAR(30) NOT NULL,
    start_time VARCHAR(5) NULL,
    stop_time VARCHAR(5) NULL,
    last_start_fired_on DATE NULL,
    last_stop_fired_on DATE NULL,
    INDEX idx_automation_rule_window_rule (rule_id),
    CONSTRAINT fk_automation_rule_window_rule FOREIGN KEY (rule_id)
        REFERENCES automation_rule (rule_id) ON DELETE CASCADE
);

INSERT INTO automation_rule_window (window_id, rule_id, position, days_of_week, start_time, stop_time,
                                    last_start_fired_on, last_stop_fired_on)
SELECT UUID(), r.rule_id, 0, r.days_of_week, r.start_time, r.stop_time, r.last_start_fired_on, r.last_stop_fired_on
FROM automation_rule r
WHERE r.trigger_type = 'SCHEDULE'
  AND r.days_of_week IS NOT NULL AND r.days_of_week <> ''
  AND NOT EXISTS (SELECT 1 FROM automation_rule_window w WHERE w.rule_id = r.rule_id);
