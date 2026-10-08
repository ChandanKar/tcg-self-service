-- V33: deleting a VM keeps its row (M35). History and metrics stay, the delete is attributed,
-- and discovery_ignored stops discovery from re-registering a still-tagged instance.
-- Reactivating the VM clears all three.
ALTER TABLE vm
    ADD COLUMN discovery_ignored BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at TIMESTAMP NULL,
    ADD COLUMN deleted_by VARCHAR(36) NULL;
