-- 3-D Secure: before a card payment made on our checkout page, the card's bank may ask its holder to prove who
-- they are, typically with a code sent by SMS. The customer leaves for the bank's page and comes back with the
-- bank's signed answer; only then is the card charged.

-- While the customer is at their bank the session waits in AUTHENTICATING. If they never come back, the session
-- opens again once the bank's challenge has expired, so they can try another card.
ALTER TABLE checkout_sessions DROP CONSTRAINT checkout_sessions_status_check;
ALTER TABLE checkout_sessions ADD CONSTRAINT checkout_sessions_status_check
    CHECK (status IN ('OPEN', 'PROCESSING', 'AUTHENTICATING', 'COMPLETED'));
ALTER TABLE checkout_sessions
    ADD COLUMN three_ds_id         uuid,
    ADD COLUMN three_ds_expires_at timestamptz,
    ADD COLUMN installments        integer NOT NULL DEFAULT 1 CHECK (installments IN (1, 2, 3, 6, 9, 12));

GRANT UPDATE (three_ds_id, three_ds_expires_at, installments) ON checkout_sessions TO kasa_app;

-- Our record of each authentication. The card token waits here, encrypted, while the customer is at the bank.
CREATE TABLE three_ds_authentications (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    session_id   uuid        NOT NULL REFERENCES checkout_sessions (id),
    card_token   text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('CHALLENGED', 'AUTHENTICATED', 'FAILED')),
    eci          text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE INDEX three_ds_authentications_session_idx ON three_ds_authentications (session_id);

ALTER TABLE three_ds_authentications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON three_ds_authentications
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON three_ds_authentications TO kasa_app;
GRANT UPDATE (status, eci, completed_at) ON three_ds_authentications TO kasa_app;

-- The bank's answer arrives through the customer's browser, which carries no tenant. This is its one way in.
CREATE FUNCTION find_three_ds_authentication(p_id uuid)
    RETURNS TABLE (tenant_id uuid, session_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT tenant_id, session_id FROM three_ds_authentications WHERE id = p_id
$$;

REVOKE ALL ON FUNCTION find_three_ds_authentication(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_three_ds_authentication(uuid) TO kasa_app;

-- How the payment's cardholder was verified, which decides who carries a fraud claim later.
ALTER TABLE payments
    ADD COLUMN three_ds_method text CHECK (three_ds_method IN ('FRICTIONLESS', 'CHALLENGE')),
    ADD COLUMN three_ds_eci    text,
    ADD COLUMN installments    integer NOT NULL DEFAULT 1 CHECK (installments IN (1, 2, 3, 6, 9, 12));

-- The checkout page needs to know where to send the customer while a challenge is open.
DROP FUNCTION find_checkout_session(uuid);

CREATE FUNCTION find_checkout_session(p_id uuid)
    RETURNS TABLE (id uuid, tenant_id uuid, merchant_name text, amount bigint, currency text, description text,
                   status text, failure_code text, payment_status text, expires_at timestamptz,
                   payment_link_id uuid, customer_id uuid, three_ds_id uuid, three_ds_expires_at timestamptz,
                   installments integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT s.id, s.tenant_id, t.name, s.amount, s.currency::text, s.description, s.status, s.failure_code, p.status,
       s.expires_at, s.payment_link_id, s.customer_id, s.three_ds_id, s.three_ds_expires_at, s.installments
FROM checkout_sessions s
JOIN tenants t ON t.id = s.tenant_id
LEFT JOIN payments p ON p.id = s.payment_id
WHERE s.id = p_id
$$;

REVOKE ALL ON FUNCTION find_checkout_session(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_checkout_session(uuid) TO kasa_app;

-- The simulated card bank's side: its challenge and the code it sent. Like the rest of the simulated bank, it
-- stands for another company's system and references none of our tables.
CREATE TABLE sim_acs_challenges (
    id            uuid PRIMARY KEY,
    merchant_name text        NOT NULL,
    amount        bigint      NOT NULL,
    currency      char(3)     NOT NULL,
    card_hint     text        NOT NULL,
    code          text        NOT NULL,
    -- Where the bank sends the customer back, with its answer, as a real bank posts to the merchant's URL.
    return_url    text        NOT NULL,
    attempts      integer     NOT NULL DEFAULT 0,
    status        text        NOT NULL CHECK (status IN ('PENDING', 'Y', 'N')),
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON sim_acs_challenges TO kasa_app;
GRANT UPDATE (attempts, status) ON sim_acs_challenges TO kasa_app;
