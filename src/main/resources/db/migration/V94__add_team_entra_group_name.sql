-- U13-T06: the linked Entra group's display name, shown on Teams ("Linked to Entra group SG-Payments-Engineering").
ALTER TABLE team ADD COLUMN entra_group_name VARCHAR(255) NULL;
