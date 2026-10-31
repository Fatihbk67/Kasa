-- A short-lived, single-purpose link to a payment page. The browser holds this unguessable id instead of an
-- API key, so a customer's device never has credentials that could act for the merchant.
CREATE TABLE checkout_sessions (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    amount       bigint      NOT NULL CHECK (amount > 0),
    currency     char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description  text,
    status       text        NOT NULL CHECK (status IN ('OPEN', 'PROCESSING', 'COMPLETED')),
    payment_id   uuid,
    failure_code text,
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments (id, tenant_id)
);

CREATE INDEX checkout_sessions_tenant_idx ON checkout_sessions (tenant_id, created_at DESC);

ALTER TABLE checkout_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkout_sessions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON checkout_sessions TO kasa_app;
GRANT UPDATE (status, payment_id, failure_code, updated_at) ON checkout_sessions TO kasa_app;

-- A customer's browser has no tenant, so this is its only view past RLS: one session, by its unguessable id.
CREATE FUNCTION find_checkout_session(p_id uuid)
    RETURNS TABLE (id uuid, tenant_id uuid, merchant_name text, amount bigint, currency text, description text,
                   status text, failure_code text, payment_status text, expires_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT s.id, s.tenant_id, t.name, s.amount, s.currency::text, s.description, s.status, s.failure_code, p.status,
       s.expires_at
FROM checkout_sessions s
JOIN tenants t ON t.id = s.tenant_id
LEFT JOIN payments p ON p.id = s.payment_id
WHERE s.id = p_id
$$;

REVOKE ALL ON FUNCTION find_checkout_session(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_checkout_session(uuid) TO kasa_app;
