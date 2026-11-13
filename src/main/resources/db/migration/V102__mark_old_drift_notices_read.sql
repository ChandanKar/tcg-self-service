-- V102 (U23-T08): mark as read the unread state-change notices in the old format.
-- Before U22-T12 every state change outside the platform was its own notice, titled
-- 'State drift detected for "<vm>": A -> B'; admins had 99+ of them unread. U22-T12 sends one
-- notice per environment titled 'Changed outside the platform: <env>', which this leaves alone.
-- Idempotent: a second run finds nothing unread in the old format.
UPDATE notification
   SET is_read = TRUE
 WHERE type = 'STATE_DRIFT_DETECTED'
   AND is_read = FALSE
   AND title LIKE 'State drift detected%';
