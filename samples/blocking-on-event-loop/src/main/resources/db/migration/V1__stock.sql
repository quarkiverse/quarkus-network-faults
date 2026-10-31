CREATE TABLE stock (
    id       SERIAL PRIMARY KEY,
    item     VARCHAR(64) NOT NULL,
    quantity INT NOT NULL
);
