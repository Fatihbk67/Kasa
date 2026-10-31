CREATE TABLE tenants (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    code           text        NOT NULL,
    type           text        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency       char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    allow_negative boolean     NOT NULL DEFAULT false,
    balance        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code, currency),
    UNIQUE (id, tenant_id, currency),
    CONSTRAINT balance_not_negative CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE journal_entries (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    description     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (id, tenant_id)
);

-- The composite foreign keys stop a posting from crossing tenants or currencies.
CREATE TABLE ledger_postings (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id   uuid    NOT NULL,
    tenant_id  uuid    NOT NULL,
    account_id uuid    NOT NULL,
    currency   char(3) NOT NULL,
    direction  text    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount     bigint  NOT NULL CHECK (amount > 0),
    FOREIGN KEY (entry_id, tenant_id) REFERENCES journal_entries (id, tenant_id),
    FOREIGN KEY (account_id, tenant_id, currency) REFERENCES ledger_accounts (id, tenant_id, currency)
);

CREATE INDEX ledger_postings_entry_idx ON ledger_postings (entry_id);
CREATE INDEX ledger_postings_account_idx ON ledger_postings (account_id, id);

-- Runs at commit, once every posting of the entry has been written.
CREATE FUNCTION assert_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target uuid;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target := NEW.id;
    ELSE
        target := NEW.entry_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM ledger_postings WHERE entry_id = target) THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: no postings', target USING ERRCODE = 'check_violation';
    END IF;

    IF EXISTS (SELECT 1
               FROM ledger_postings
               WHERE entry_id = target
               GROUP BY currency
               HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0) THEN
        RAISE EXCEPTION 'journal entry % is unbalanced', target USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();

CREATE CONSTRAINT TRIGGER ledger_postings_balanced
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();

-- The ledger is append-only: mistakes are corrected with a reversing entry.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_postings_append_only
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_postings_no_truncate
    BEFORE TRUNCATE ON ledger_postings
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
