ALTER TABLE users
    ADD COLUMN totp_secret       text,
    ADD COLUMN totp_enabled_at   timestamptz,
    ADD COLUMN totp_last_step    bigint  NOT NULL DEFAULT 0,
    ADD COLUMN totp_failures     integer NOT NULL DEFAULT 0,
    ADD COLUMN totp_locked_until timestamptz,
    ADD CONSTRAINT enabled_totp_has_a_secret CHECK (totp_enabled_at IS NULL OR totp_secret IS NOT NULL);

GRANT UPDATE (totp_secret, totp_enabled_at, totp_last_step, totp_failures, totp_locked_until) ON users TO kasa_app;

DROP FUNCTION find_login_credentials(text);

CREATE FUNCTION find_login_credentials(p_email text)
    RETURNS TABLE (user_id uuid, tenant_id uuid, password_hash text, token_version integer, two_factor boolean)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT u.id, u.tenant_id, u.password_hash, u.token_version, u.totp_enabled_at IS NOT NULL
FROM users u
WHERE u.email = p_email AND u.disabled_at IS NULL
$$;

REVOKE ALL ON FUNCTION find_login_credentials(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_login_credentials(text) TO kasa_app;

-- Proof that the password step passed. It opens nothing but the second step, and carries the token version
-- so that changing the password also cancels any second step still waiting.
CREATE TABLE login_challenges (
    token_hash    text PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    user_id       uuid        NOT NULL REFERENCES users (id),
    token_version integer     NOT NULL,
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE recovery_codes (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants (id),
    user_id   uuid NOT NULL REFERENCES users (id),
    code_hash text NOT NULL,
    used_at   timestamptz,
    UNIQUE (user_id, code_hash)
);

CREATE TABLE password_reset_tokens (
    token_hash text PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id) WHERE used_at IS NULL;

ALTER TABLE login_challenges ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON login_challenges
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE recovery_codes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recovery_codes
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON password_reset_tokens
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON login_challenges, recovery_codes, password_reset_tokens TO kasa_app;
GRANT UPDATE (used_at) ON login_challenges, recovery_codes, password_reset_tokens TO kasa_app;

-- Someone mid-login or holding a reset link has no tenant yet, so these are their only views past RLS.
CREATE FUNCTION find_login_challenge(p_hash text)
    RETURNS TABLE (tenant_id uuid, user_id uuid, token_version integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT c.tenant_id, c.user_id, c.token_version
FROM login_challenges c
WHERE c.token_hash = p_hash AND c.used_at IS NULL AND c.expires_at > now()
$$;

CREATE FUNCTION find_password_reset(p_hash text)
    RETURNS TABLE (tenant_id uuid, user_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT r.tenant_id, r.user_id
FROM password_reset_tokens r
WHERE r.token_hash = p_hash AND r.used_at IS NULL AND r.expires_at > now()
$$;

REVOKE ALL ON FUNCTION find_login_challenge(text), find_password_reset(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_login_challenge(text), find_password_reset(text) TO kasa_app;

-- Personal accounts for the people who run the platform, who belong to no tenant. A second factor is not
-- optional here: these accounts can move every merchant's money.
CREATE TABLE platform_admins (
    id                uuid PRIMARY KEY,
    email             text        NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash     text        NOT NULL,
    totp_secret       text        NOT NULL,
    totp_last_step    bigint      NOT NULL DEFAULT 0,
    totp_failures     integer     NOT NULL DEFAULT 0,
    totp_locked_until timestamptz,
    token_version     integer     NOT NULL DEFAULT 1,
    disabled_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON platform_admins TO kasa_app;
GRANT UPDATE (totp_last_step, totp_failures, totp_locked_until, token_version, disabled_at) ON platform_admins TO kasa_app;

-- Who did what across all tenants. The application can only add to it.
CREATE TABLE admin_audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now(),
    actor       text        NOT NULL,
    action      text        NOT NULL,
    target      text,
    ip          text
);

GRANT SELECT, INSERT ON admin_audit_log TO kasa_app;

-- Secrets written before encryption existed. The key lives in the application, so it has to do this conversion.
GRANT UPDATE (secret) ON webhook_endpoints TO kasa_app;

CREATE FUNCTION find_plaintext_webhook_secrets(p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid, secret text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT e.id, e.tenant_id, e.secret
FROM webhook_endpoints e
WHERE e.secret NOT LIKE 'enc1:%'
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION find_plaintext_webhook_secrets(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_plaintext_webhook_secrets(integer) TO kasa_app;
