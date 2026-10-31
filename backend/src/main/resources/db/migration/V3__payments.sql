CREATE TABLE payments (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'FAILED', 'VOIDED',
                                                           'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    captured_amount bigint      NOT NULL DEFAULT 0,
    refunded_amount bigint      NOT NULL DEFAULT 0,
    fee_amount      bigint      NOT NULL DEFAULT 0,
    description     text,
    bank_reference  text,
    failure_code    text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    CONSTRAINT captured_within_amount CHECK (captured_amount BETWEEN 0 AND amount),
    CONSTRAINT refunded_within_captured CHECK (refunded_amount BETWEEN 0 AND captured_amount),
    CONSTRAINT fee_within_captured CHECK (fee_amount BETWEEN 0 AND captured_amount)
);

CREATE INDEX payments_listing_idx ON payments (tenant_id, created_at DESC, id DESC);

-- Every bank call is one row, written before the call. Its id doubles as the bank's idempotency reference,
-- so an operation whose outcome was lost can be looked up instead of guessed.
CREATE TABLE payment_operations (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    payment_id      uuid        NOT NULL,
    type            text        NOT NULL CHECK (type IN ('AUTHORIZE', 'CAPTURE', 'VOID', 'REFUND')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    bank_reference  text,
    failure_code    text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments (id, tenant_id),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX payment_operations_payment_idx ON payment_operations (payment_id, created_at);
CREATE INDEX payment_operations_pending_idx ON payment_operations (created_at) WHERE status = 'PENDING';

-- A payment can have only one bank call in flight, so a capture and a void can never race at the bank.
CREATE UNIQUE INDEX payment_operations_one_pending ON payment_operations (payment_id) WHERE status = 'PENDING';

CREATE FUNCTION assert_payment_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'PENDING' AND NEW.status IN ('AUTHORIZED', 'DECLINED', 'FAILED'))
            OR (OLD.status = 'AUTHORIZED' AND NEW.status IN ('CAPTURED', 'VOIDED'))
            OR (OLD.status = 'CAPTURED' AND NEW.status IN ('PARTIALLY_REFUNDED', 'REFUNDED'))
            OR (OLD.status = 'PARTIALLY_REFUNDED' AND NEW.status = 'REFUNDED')
        ) THEN
        RAISE EXCEPTION 'illegal payment transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payments_valid_transition
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION assert_payment_transition();

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE payment_operations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_operations
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON payments, payment_operations TO kasa_app;
GRANT UPDATE (status, captured_amount, refunded_amount, fee_amount, bank_reference, failure_code, updated_at)
    ON payments TO kasa_app;
GRANT UPDATE (status, bank_reference, failure_code, updated_at) ON payment_operations TO kasa_app;

-- The recovery job serves all tenants, so it needs this one narrow view past RLS.
CREATE FUNCTION find_stale_payment_operations(p_older_than_seconds bigint, p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid, payment_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT o.id, o.tenant_id, o.payment_id
FROM payment_operations o
WHERE o.status = 'PENDING' AND o.created_at < now() - make_interval(secs => p_older_than_seconds)
ORDER BY o.created_at
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION find_stale_payment_operations(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_stale_payment_operations(bigint, integer) TO kasa_app;
