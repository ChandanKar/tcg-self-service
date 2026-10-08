-- V29: Optimistic locking on vm (H14).
-- An operation step saved a VM copy loaded before the stop, overwriting the EKS node-group
-- sizes the provider had saved meanwhile. Status, metadata and tag-sync writes are now targeted
-- UPDATEs that bump this version; a stale whole-entity save fails instead of losing data.
ALTER TABLE vm ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
