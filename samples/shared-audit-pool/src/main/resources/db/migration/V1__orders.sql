CREATE TABLE orders (
    id   SERIAL PRIMARY KEY,
    item VARCHAR(64) NOT NULL
);

-- the audit log lives in the orders database while it shares the pool
CREATE TABLE audit_log (
    id    SERIAL PRIMARY KEY,
    event VARCHAR(256) NOT NULL
);
