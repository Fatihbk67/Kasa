-- Personal data lives only here. Anonymizing a customer blanks this row and leaves the payments, which the
-- law requires keeping, pointing at nobody in particular.
CREATE TABLE customers (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    email         text,
    name          text,
    anonymized_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    CONSTRAINT anonymized_holds_no_personal_data CHECK (anonymized_at IS NULL OR (email IS NULL AND name IS NULL))
);

CREATE UNIQUE INDEX customers_email_per_tenant ON customers (tenant_id, email) WHERE email IS NOT NULL;
CREATE INDEX customers_listing_idx ON customers (tenant_id, created_at DESC, id DESC);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customers
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON customers TO kasa_app;
GRANT UPDATE (email, name, anonymized_at, updated_at) ON customers TO kasa_app;

ALTER TABLE payments
    ADD COLUMN customer_id     uuid,
    ADD COLUMN disputed_amount bigint NOT NULL DEFAULT 0,
    ADD FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id),
    ADD CONSTRAINT returned_within_captured
        CHECK (disputed_amount >= 0 AND refunded_amount + disputed_amount <= captured_amount);

CREATE INDEX payments_customer_idx ON payments (customer_id, created_at DESC) WHERE customer_id IS NOT NULL;

GRANT UPDATE (disputed_amount) ON payments TO kasa_app;

-- A reusable link a merchant can share anywhere. Each visitor gets a checkout session of their own.
CREATE TABLE payment_links (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    slug         text        NOT NULL UNIQUE,
    title        text        NOT NULL,
    description  text,
    amount       bigint CHECK (amount > 0),
    currency     char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    max_payments integer CHECK (max_payments > 0),
    expires_at   timestamptz,
    disabled_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE INDEX payment_links_listing_idx ON payment_links (tenant_id, created_at DESC, id DESC);

ALTER TABLE payment_links ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_links
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON payment_links TO kasa_app;
GRANT UPDATE (disabled_at, updated_at) ON payment_links TO kasa_app;

ALTER TABLE checkout_sessions
    ADD COLUMN payment_link_id uuid,
    ADD COLUMN customer_id     uuid,
    ADD FOREIGN KEY (payment_link_id, tenant_id) REFERENCES payment_links (id, tenant_id),
    ADD FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id);

CREATE INDEX checkout_sessions_link_idx ON checkout_sessions (payment_link_id) WHERE payment_link_id IS NOT NULL;

-- The return type changes, which CREATE OR REPLACE does not allow.
DROP FUNCTION find_checkout_session(uuid);

CREATE FUNCTION find_checkout_session(p_id uuid)
    RETURNS TABLE (id uuid, tenant_id uuid, merchant_name text, amount bigint, currency text, description text,
                   status text, failure_code text, payment_status text, expires_at timestamptz,
                   payment_link_id uuid, customer_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT s.id, s.tenant_id, t.name, s.amount, s.currency::text, s.description, s.status, s.failure_code, p.status,
       s.expires_at, s.payment_link_id, s.customer_id
FROM checkout_sessions s
JOIN tenants t ON t.id = s.tenant_id
LEFT JOIN payments p ON p.id = s.payment_id
WHERE s.id = p_id
$$;

REVOKE ALL ON FUNCTION find_checkout_session(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_checkout_session(uuid) TO kasa_app;

-- A visitor has no tenant, so this is their only view past RLS: one link, by its unguessable slug.
CREATE FUNCTION find_payment_link(p_slug text)
    RETURNS TABLE (id uuid, tenant_id uuid, merchant_name text, title text, description text, amount bigint,
                   currency text, max_payments integer, expires_at timestamptz, disabled_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT l.id, l.tenant_id, t.name, l.title, l.description, l.amount, l.currency::text, l.max_payments, l.expires_at,
       l.disabled_at
FROM payment_links l
JOIN tenants t ON t.id = l.tenant_id
WHERE l.slug = p_slug
$$;

REVOKE ALL ON FUNCTION find_payment_link(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_payment_link(text) TO kasa_app;

-- Starts when the bank says so, not the merchant, and the bank takes the money back at that moment.
CREATE TABLE disputes (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    payment_id             uuid        NOT NULL UNIQUE,
    amount                 bigint      NOT NULL CHECK (amount > 0),
    currency               char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reason                 text        NOT NULL CHECK (reason IN ('FRAUDULENT', 'PRODUCT_NOT_RECEIVED', 'DUPLICATE',
                                                                  'PRODUCT_UNACCEPTABLE', 'OTHER')),
    status                 text        NOT NULL CHECK (status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW', 'WON', 'LOST')),
    fee_amount             bigint      NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    respond_by             timestamptz NOT NULL,
    evidence_explanation   text,
    evidence_tracking      text,
    evidence_communication text,
    evidence_submitted_at  timestamptz,
    resolved_at            timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments (id, tenant_id)
);

CREATE INDEX disputes_listing_idx ON disputes (tenant_id, created_at DESC, id DESC);
CREATE INDEX disputes_open_idx ON disputes (status, respond_by) WHERE status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW');

CREATE FUNCTION assert_dispute_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'NEEDS_RESPONSE' AND NEW.status IN ('UNDER_REVIEW', 'LOST'))
            OR (OLD.status = 'UNDER_REVIEW' AND NEW.status IN ('WON', 'LOST'))
        ) THEN
        RAISE EXCEPTION 'illegal dispute transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER disputes_valid_transition
    BEFORE UPDATE ON disputes
    FOR EACH ROW EXECUTE FUNCTION assert_dispute_transition();

ALTER TABLE disputes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON disputes
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON disputes TO kasa_app;
GRANT UPDATE (status, fee_amount, evidence_explanation, evidence_tracking, evidence_communication,
              evidence_submitted_at, resolved_at, updated_at) ON disputes TO kasa_app;

-- The deadline and decision jobs serve all tenants, so they need this one narrow view past RLS.
CREATE FUNCTION find_due_disputes(p_reviewed_for_seconds bigint, p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid, status text, has_tracking boolean)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT d.id, d.tenant_id, d.status, d.evidence_tracking IS NOT NULL
FROM disputes d
WHERE (d.status = 'NEEDS_RESPONSE' AND d.respond_by < now())
   OR (d.status = 'UNDER_REVIEW' AND d.evidence_submitted_at < now() - make_interval(secs => p_reviewed_for_seconds))
ORDER BY d.respond_by
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION find_due_disputes(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_due_disputes(bigint, integer) TO kasa_app;

-- A dispute moves money as surely as a refund does, so its movements are operations too. That brings them
-- into the payment's history, into settlement and into reconciliation without a second set of rules.
ALTER TABLE payment_operations DROP CONSTRAINT payment_operations_type_check;
ALTER TABLE payment_operations ADD CONSTRAINT payment_operations_type_check
    CHECK (type IN ('AUTHORIZE', 'CAPTURE', 'VOID', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE'));

ALTER TABLE payout_items DROP CONSTRAINT payout_items_type_check;
ALTER TABLE payout_items ADD CONSTRAINT payout_items_type_check
    CHECK (type IN ('CAPTURE', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE'));

DROP INDEX payment_operations_settleable_idx;
CREATE INDEX payment_operations_settleable_idx ON payment_operations (updated_at)
    WHERE status = 'SUCCEEDED' AND type IN ('CAPTURE', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE');

-- The dispute fee stays out: it moves between our own accounts and the bank never sees it.
CREATE OR REPLACE VIEW bank_movements AS
SELECT o.id AS source_id, o.tenant_id, o.type, o.amount, p.currency::text AS currency, o.bank_reference,
       o.updated_at AS booked_at
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL')
UNION ALL
SELECT id, tenant_id, 'PAYOUT', amount, currency::text, bank_reference, paid_at
FROM payouts
WHERE status = 'PAID';

CREATE OR REPLACE FUNCTION find_unsettled_tenants(p_cutoff timestamptz)
    RETURNS TABLE (tenant_id uuid, currency text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT DISTINCT o.tenant_id, p.currency::text
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
WHERE o.status = 'SUCCEEDED'
  AND o.type IN ('CAPTURE', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE')
  AND o.updated_at < p_cutoff
  AND NOT EXISTS (SELECT 1 FROM payout_items i WHERE i.operation_id = o.id AND i.released_at IS NULL)
$$;
