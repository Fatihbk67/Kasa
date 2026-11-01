-- The simulated bank's own records. They stand for another company's systems, so nothing here references our
-- tables, and they outlive restarts as a real bank's would. Deleting and rewriting is allowed only because the
-- bank's test hooks use it to reproduce errors found in real bank files.
CREATE TABLE sim_bank_operations (
    operation_id uuid PRIMARY KEY,
    approved     boolean     NOT NULL,
    reference    text,
    decline_code text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sim_bank_authorizations (
    reference  text PRIMARY KEY,
    card_token text    NOT NULL,
    currency   char(3) NOT NULL
);

CREATE TABLE sim_bank_statement_lines (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference text        NOT NULL,
    type      text        NOT NULL,
    amount    bigint      NOT NULL,
    currency  char(3)     NOT NULL,
    booked_at timestamptz NOT NULL,
    booked_on date        NOT NULL
);

CREATE INDEX sim_bank_statement_lines_day_idx ON sim_bank_statement_lines (booked_on, id);
CREATE INDEX sim_bank_statement_lines_reference_idx ON sim_bank_statement_lines (reference);

GRANT SELECT, INSERT ON sim_bank_operations, sim_bank_authorizations TO kasa_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON sim_bank_statement_lines TO kasa_app;
