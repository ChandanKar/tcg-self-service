-- V26: Mark access requests that extend an existing, about-to-expire grant (M19).
-- Approving an extension adds the days to the grant's current expiry (when that is still in the
-- future) instead of to the approval time; reviewers and My Account also show it as an extension.
ALTER TABLE environment_access_request
    ADD COLUMN is_extension BOOLEAN NOT NULL DEFAULT FALSE;
