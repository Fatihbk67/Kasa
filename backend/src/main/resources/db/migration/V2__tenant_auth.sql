CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_email_normalized CHECK (email = lower(email))
);

CREATE INDEX users_tenant_idx ON users (tenant_id);

CREATE TABLE api_keys (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    name       text        NOT NULL,
    key_prefix text        NOT NULL UNIQUE,
    key_hash   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

CREATE INDEX api_keys_tenant_idx ON api_keys (tenant_id);

-- NULLIF: a transaction-local setting reverts to an empty string, not NULL, once the transaction ends.
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE AS
$$
SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;

-- With no tenant bound the policies match nothing, so a forgotten binding fails closed.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = current_tenant_id())
    WITH CHECK (id = current_tenant_id());

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_keys
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE ledger_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_accounts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journal_entries
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE ledger_postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger_postings
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Least privilege: the ledger stays append-only for the application role, and only a balance can change.
GRANT SELECT, INSERT ON tenants, users, ledger_accounts, journal_entries, ledger_postings TO kasa_app;
GRANT UPDATE (balance) ON ledger_accounts TO kasa_app;
GRANT SELECT, INSERT ON api_keys TO kasa_app;
GRANT UPDATE (revoked_at) ON api_keys TO kasa_app;

-- Login and API key checks run before any tenant is known. These two functions are the only way
-- past RLS, and they return nothing beyond what those checks need.
CREATE FUNCTION find_login_credentials(p_email text)
    RETURNS TABLE (user_id uuid, tenant_id uuid, password_hash text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT u.id, u.tenant_id, u.password_hash FROM users u WHERE u.email = p_email
$$;

CREATE FUNCTION find_active_api_key(p_prefix text)
    RETURNS TABLE (tenant_id uuid, key_hash text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT k.tenant_id, k.key_hash FROM api_keys k WHERE k.key_prefix = p_prefix AND k.revoked_at IS NULL
$$;

REVOKE ALL ON FUNCTION find_login_credentials(text), find_active_api_key(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_login_credentials(text), find_active_api_key(text) TO kasa_app;
