-- E32-T05: the destroy warnings sent (a day and an hour before) and the engine record of the destroy run.
ALTER TABLE provisioning_request ADD COLUMN warned_day BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE provisioning_request ADD COLUMN warned_hour BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE provisioning_request ADD COLUMN destroy_record_id VARCHAR(200) NULL;
