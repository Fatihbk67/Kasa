-- Real card payments through iyzico's sandbox. The customer pays on iyzico's own hosted page, so card details
-- never reach this application; iyzico then sends the customer back with a token we exchange for the result.

-- Which network carried a payment, and the card as iyzico describes it (brand and last four digits only).
ALTER TABLE payments
    ADD COLUMN processor  text NOT NULL DEFAULT 'SIMULATED' CHECK (processor IN ('SIMULATED', 'IYZICO')),
    ADD COLUMN card_label text;

GRANT UPDATE (processor, card_label) ON payments TO kasa_app;

-- Our side: each visit a customer makes to iyzico's page from a checkout session.
CREATE TABLE iyzico_checkouts (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenants (id),
    session_id        uuid        NOT NULL REFERENCES checkout_sessions (id),
    token             text        NOT NULL UNIQUE,
    page_url          text        NOT NULL,
    status            text        NOT NULL DEFAULT 'STARTED' CHECK (status IN ('STARTED', 'PAID', 'FAILED', 'CANCELLED')),
    iyzico_payment_id text,
    failure           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    completed_at      timestamptz
);

CREATE INDEX iyzico_checkouts_session_idx ON iyzico_checkouts (session_id);

ALTER TABLE iyzico_checkouts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iyzico_checkouts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON iyzico_checkouts TO kasa_app;
GRANT UPDATE (status, iyzico_payment_id, failure, completed_at) ON iyzico_checkouts TO kasa_app;

-- iyzico returns the customer through their browser, which carries no tenant. These are its only ways in.
CREATE FUNCTION find_iyzico_checkout_by_token(p_token text)
    RETURNS TABLE (id uuid, tenant_id uuid, session_id uuid, status text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT id, tenant_id, session_id, status FROM iyzico_checkouts WHERE token = p_token
$$;

REVOKE ALL ON FUNCTION find_iyzico_checkout_by_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_iyzico_checkout_by_token(text) TO kasa_app;

-- The checkout page sends a customer who left iyzico's page half way back to it.
CREATE FUNCTION find_iyzico_checkout_page(p_id uuid)
    RETURNS TABLE (page_url text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT page_url FROM iyzico_checkouts WHERE id = p_id AND status = 'STARTED'
$$;

REVOKE ALL ON FUNCTION find_iyzico_checkout_page(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_iyzico_checkout_page(uuid) TO kasa_app;

-- The gateway's side, kept apart from our tables like the simulated bank's: what iyzico confirmed it charged,
-- and every call made to it, so a repeated operation is answered from here instead of moving money twice.
CREATE TABLE iyzico_payments (
    payment_id   text PRIMARY KEY,
    amount       bigint      NOT NULL,
    currency     char(3)     NOT NULL,
    installment  integer     NOT NULL,
    card_label   text,
    -- The one authorisation that may claim this charge, so it can back a single payment of ours only.
    operation_id uuid UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE iyzico_operations (
    operation_id uuid PRIMARY KEY,
    -- SENT means the request may have reached iyzico and its outcome is unknown; it is never guessed.
    status       text        NOT NULL CHECK (status IN ('SENT', 'APPROVED', 'DECLINED')),
    reference    text,
    decline_code text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON iyzico_payments TO kasa_app;
GRANT UPDATE (operation_id) ON iyzico_payments TO kasa_app;
GRANT SELECT, INSERT, UPDATE ON iyzico_operations TO kasa_app;
