-- E19-T02: holiday calendars. A schedule rule linked to a calendar skips its start times
-- (SKIP_START) or all its times (SKIP_ALL) on the calendar's dates, in the rule's own timezone.

CREATE TABLE holiday_calendar (
    calendar_id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    created_by_user_id VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_holiday_calendar_name UNIQUE (name)
);

CREATE TABLE holiday_date (
    holiday_id VARCHAR(36) NOT NULL PRIMARY KEY,
    calendar_id VARCHAR(36) NOT NULL,
    holiday_date DATE NOT NULL,
    label VARCHAR(100) NULL,
    CONSTRAINT uk_holiday_date_calendar_day UNIQUE (calendar_id, holiday_date),
    CONSTRAINT fk_holiday_date_calendar FOREIGN KEY (calendar_id)
        REFERENCES holiday_calendar (calendar_id) ON DELETE CASCADE
);

ALTER TABLE automation_rule
    ADD COLUMN holiday_calendar_id VARCHAR(36) NULL,
    ADD COLUMN holiday_behavior VARCHAR(20) NOT NULL DEFAULT 'SKIP_START',
    ADD CONSTRAINT fk_automation_rule_holiday_calendar FOREIGN KEY (holiday_calendar_id)
        REFERENCES holiday_calendar (calendar_id) ON DELETE SET NULL;
