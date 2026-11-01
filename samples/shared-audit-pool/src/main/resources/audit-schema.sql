CREATE TABLE audit_log (
    id    SERIAL PRIMARY KEY,
    event VARCHAR(256) NOT NULL
);
