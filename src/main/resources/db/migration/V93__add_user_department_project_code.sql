-- U13-T01: a person's department and project code, collected on Add people and kept with the user.
-- Both are optional; the project code is free text per person (no project table).
ALTER TABLE app_user
    ADD COLUMN department VARCHAR(100) NULL,
    ADD COLUMN project_code VARCHAR(50) NULL;
