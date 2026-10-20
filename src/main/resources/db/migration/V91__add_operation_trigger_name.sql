-- U08-T01: what started a run, by name ("Nightly batch" for a schedule rule). Manual runs leave it null.
ALTER TABLE operation_execution ADD COLUMN trigger_name VARCHAR(200) NULL;
