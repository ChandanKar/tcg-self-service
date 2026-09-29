-- V22: Email send log — an admin-visible record of every email the app has actually attempted
-- to send (weekly reports, access-lifecycle emails, stop-environment broadcasts, ...), so
-- "who have we emailed and when" is answerable from the UI instead of only the app log.
--
-- Only real send attempts are logged (notification.email.enabled=true and recipients present) —
-- a suppressed send (feature flag off) is not recorded, since nothing was actually attempted.

CREATE TABLE email_log (
    log_id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    notification_type  VARCHAR(50)  NULL,
    environment_id     VARCHAR(36)  NULL,
    recipients         TEXT         NOT NULL,
    recipient_count     INT          NOT NULL,
    subject            VARCHAR(500) NOT NULL,
    success            BOOLEAN      NOT NULL,
    error_message      TEXT         NULL,
    sent_by_user_id    VARCHAR(36)  NULL,
    sent_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_email_log_sent_at ON email_log (sent_at);
CREATE INDEX ix_email_log_environment ON email_log (environment_id);
CREATE INDEX ix_email_log_type ON email_log (notification_type);
