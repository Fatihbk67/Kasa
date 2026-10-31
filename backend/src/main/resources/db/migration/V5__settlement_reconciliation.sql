CREATE TABLE payouts (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    currency        char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount          bigint      NOT NULL CHECK (amount > 0),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    settlement_date date        NOT NULL,
    cutoff          timestamptz NOT NULL,
    bank_reference  text,
    failure_code    text,
    paid_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE INDEX payouts_listing_idx ON payouts (tenant_id, created_at DESC, id DESC);
CREATE INDEX payouts_pending_idx ON payouts (created_at) WHERE status = 'PENDING';
CREATE INDEX payouts_bank_reference_idx ON payouts (bank_reference) WHERE bank_reference IS NOT NULL;

-- At most one live payout per day, so a repeated or concurrent job run cannot pay a merchant twice.
-- A failed payout does not count, which lets a later run try again.
CREATE UNIQUE INDEX payouts_one_per_day ON payouts (tenant_id, currency, settlement_date) WHERE status <> 'FAILED';

CREATE TABLE payout_items (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid   NOT NULL,
    payout_id    uuid   NOT NULL,
    operation_id uuid   NOT NULL REFERENCES payment_operations (id),
    payment_id   uuid   NOT NULL,
    type         text   NOT NULL CHECK (type IN ('CAPTURE', 'REFUND')),
    gross_amount bigint NOT NULL CHECK (gross_amount > 0),
    fee_amount   bigint NOT NULL CHECK (fee_amount >= 0),
    net_amount   bigint NOT NULL,
    released_at  timestamptz,
    FOREIGN KEY (payout_id, tenant_id) REFERENCES payouts (id, tenant_id),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments (id, tenant_id)
);

CREATE INDEX payout_items_payout_idx ON payout_items (payout_id);

-- An operation can belong to only one live payout. A failed payout releases its items for the next run.
CREATE UNIQUE INDEX payout_items_settled_once ON payout_items (operation_id) WHERE released_at IS NULL;

CREATE INDEX payment_operations_settleable_idx ON payment_operations (updated_at)
    WHERE status = 'SUCCEEDED' AND type IN ('CAPTURE', 'REFUND');
CREATE INDEX payment_operations_bank_reference_idx ON payment_operations (bank_reference)
    WHERE bank_reference IS NOT NULL;

CREATE FUNCTION assert_payout_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'PENDING' AND NEW.status IN ('PAID', 'FAILED')) THEN
        RAISE EXCEPTION 'illegal payout transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payouts_valid_transition
    BEFORE UPDATE ON payouts
    FOR EACH ROW EXECUTE FUNCTION assert_payout_transition();

ALTER TABLE payouts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payouts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE payout_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payout_items
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON payouts, payout_items TO kasa_app;
GRANT UPDATE (status, bank_reference, failure_code, paid_at, updated_at) ON payouts TO kasa_app;
GRANT UPDATE (released_at) ON payout_items TO kasa_app;

-- Reconciliation compares the platform as a whole with the bank, so these tables have no tenant isolation.
-- Only the platform operator's admin API reads them.
CREATE TABLE reconciliation_runs (
    id                uuid PRIMARY KEY,
    statement_date    date        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    bank_line_count   integer     NOT NULL DEFAULT 0,
    matched_count     integer     NOT NULL DEFAULT 0,
    discrepancy_count integer     NOT NULL DEFAULT 0,
    error             text,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_runs_date_idx ON reconciliation_runs (statement_date DESC, created_at DESC);

CREATE TABLE reconciliation_discrepancies (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          uuid NOT NULL REFERENCES reconciliation_runs (id),
    kind            text NOT NULL CHECK (kind IN ('MISSING_IN_BANK', 'MISSING_IN_KASA', 'AMOUNT_MISMATCH',
                                                  'DETAILS_MISMATCH', 'DUPLICATE_IN_BANK', 'LEDGER_DRIFT')),
    bank_reference  text,
    tenant_id       uuid,
    source_id       uuid,
    expected_amount bigint,
    actual_amount   bigint,
    detail          text
);

CREATE INDEX reconciliation_discrepancies_run_idx ON reconciliation_discrepancies (run_id);

GRANT SELECT, INSERT ON reconciliation_runs, reconciliation_discrepancies TO kasa_app;

-- Every movement of money the bank should know about. Not granted to the application role: the
-- functions below are its only access, because settlement and reconciliation span all tenants.
CREATE VIEW bank_movements AS
SELECT o.id AS source_id, o.tenant_id, o.type, o.amount, p.currency::text AS currency, o.bank_reference,
       o.updated_at AS booked_at
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND')
UNION ALL
SELECT id, tenant_id, 'PAYOUT', amount, currency::text, bank_reference, paid_at
FROM payouts
WHERE status = 'PAID';

CREATE FUNCTION find_bank_movements_booked_between(p_from timestamptz, p_to timestamptz)
    RETURNS TABLE (source_id uuid, tenant_id uuid, type text, amount bigint, currency text, bank_reference text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT m.source_id, m.tenant_id, m.type, m.amount, m.currency, m.bank_reference
FROM bank_movements m
WHERE m.booked_at >= p_from AND m.booked_at < p_to
$$;

CREATE FUNCTION find_bank_movements_by_references(p_references text[])
    RETURNS TABLE (source_id uuid, tenant_id uuid, type text, amount bigint, currency text, bank_reference text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT m.source_id, m.tenant_id, m.type, m.amount, m.currency, m.bank_reference
FROM bank_movements m
WHERE m.bank_reference = ANY (p_references)
$$;

CREATE FUNCTION find_unsettled_tenants(p_cutoff timestamptz)
    RETURNS TABLE (tenant_id uuid, currency text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT DISTINCT o.tenant_id, p.currency::text
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND') AND o.updated_at < p_cutoff
  AND NOT EXISTS (SELECT 1 FROM payout_items i WHERE i.operation_id = o.id AND i.released_at IS NULL)
$$;

CREATE FUNCTION find_stale_payouts(p_older_than_seconds bigint, p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT p.id, p.tenant_id
FROM payouts p
WHERE p.status = 'PENDING' AND p.created_at < now() - make_interval(secs => p_older_than_seconds)
ORDER BY p.created_at
LIMIT p_limit
$$;

CREATE FUNCTION find_all_drifted_ledger_accounts()
    RETURNS TABLE (id uuid, tenant_id uuid, code text, balance bigint, computed bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT a.id, a.tenant_id, a.code, a.balance, c.computed
FROM ledger_accounts a
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(CASE WHEN (l.direction = 'DEBIT') = (a.type IN ('ASSET', 'EXPENSE'))
                             THEN l.amount ELSE -l.amount END), 0)::bigint AS computed
    FROM ledger_postings l
    WHERE l.account_id = a.id
) c
WHERE a.balance <> c.computed
$$;

REVOKE ALL ON FUNCTION find_bank_movements_booked_between(timestamptz, timestamptz),
    find_bank_movements_by_references(text[]),
    find_unsettled_tenants(timestamptz),
    find_stale_payouts(bigint, integer),
    find_all_drifted_ledger_accounts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_bank_movements_booked_between(timestamptz, timestamptz),
    find_bank_movements_by_references(text[]),
    find_unsettled_tenants(timestamptz),
    find_stale_payouts(bigint, integer),
    find_all_drifted_ledger_accounts() TO kasa_app;
