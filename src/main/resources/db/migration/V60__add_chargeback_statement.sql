-- E23-T07: frozen monthly chargeback statements. Once a month is FINAL its stored payload is
-- served instead of a live computation, so late billing data cannot change an invoiced month.

CREATE TABLE chargeback_statement (
    statement_id VARCHAR(36) NOT NULL PRIMARY KEY,
    statement_month CHAR(7) NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    generated_by VARCHAR(36) NULL,
    payload MEDIUMTEXT NOT NULL,
    total DECIMAL(14,2) NOT NULL,
    status VARCHAR(10) NOT NULL,
    CONSTRAINT uk_chargeback_statement_month UNIQUE (statement_month)
);
