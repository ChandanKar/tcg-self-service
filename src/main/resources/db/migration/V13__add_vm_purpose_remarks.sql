-- V13: Purpose (associated client/project) and free-form remarks for a VM.

ALTER TABLE vm ADD COLUMN purpose VARCHAR(255) NULL;
ALTER TABLE vm ADD COLUMN remarks TEXT NULL;
