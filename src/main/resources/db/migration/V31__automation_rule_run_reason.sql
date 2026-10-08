-- V31: Why an automation rule last ran the way it did, and why it was switched off (M16).
-- last_run_reason: a code (OK, NOTHING_TO_DO, LOCKED, OPERATION_IN_PROGRESS, ENVIRONMENT_INACTIVE,
--                  CREATOR_INACTIVE, SCOPE_MISSING, ERROR, MISSED_WINDOW) so the UI can count, e.g.,
--                  lock skips without parsing the detail text.
-- disabled_reason: set when the system disables a rule (deactivated environment, inactive creator,
--                  deleted target); cleared when someone re-enables it.
ALTER TABLE automation_rule
    ADD COLUMN last_run_reason VARCHAR(30) NULL,
    ADD COLUMN disabled_reason VARCHAR(255) NULL;
