ALTER TABLE users
    ADD COLUMN role          text        NOT NULL DEFAULT 'OWNER' CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER')),
    ADD COLUMN disabled_at   timestamptz,
    ADD COLUMN token_version integer     NOT NULL DEFAULT 1;

GRANT UPDATE (role, disabled_at, password_hash, token_version) ON users TO kasa_app;
GRANT UPDATE (name) ON tenants TO kasa_app;

-- A signed token cannot be recalled, so every request re-checks the user here. Disabling a member or
-- changing a role therefore takes effect at once, not when the token happens to expire.
CREATE FUNCTION find_user_access(p_user_id uuid)
    RETURNS TABLE (tenant_id uuid, email text, role text, token_version integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT u.tenant_id, u.email, u.role, u.token_version FROM users u WHERE u.id = p_user_id AND u.disabled_at IS NULL
$$;

DROP FUNCTION find_login_credentials(text);

CREATE FUNCTION find_login_credentials(p_email text)
    RETURNS TABLE (user_id uuid, tenant_id uuid, password_hash text, token_version integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT u.id, u.tenant_id, u.password_hash, u.token_version FROM users u WHERE u.email = p_email AND u.disabled_at IS NULL
$$;

CREATE TABLE team_invitations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    email       text        NOT NULL CHECK (email = lower(email)),
    role        text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER')),
    token_hash  text        NOT NULL UNIQUE,
    invited_by  uuid        NOT NULL REFERENCES users (id),
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX team_invitations_one_open ON team_invitations (tenant_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

ALTER TABLE team_invitations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON team_invitations
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON team_invitations TO kasa_app;
GRANT UPDATE (accepted_at, revoked_at) ON team_invitations TO kasa_app;

-- The person accepting has no account or tenant yet. The token is the only key, and only its hash is stored.
CREATE FUNCTION find_invitation(p_token_hash text)
    RETURNS TABLE (id uuid, tenant_id uuid, tenant_name text, email text, role text, expires_at timestamptz,
                   accepted_at timestamptz, revoked_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT i.id, i.tenant_id, t.name, i.email, i.role, i.expires_at, i.accepted_at, i.revoked_at
FROM team_invitations i
JOIN tenants t ON t.id = i.tenant_id
WHERE i.token_hash = p_token_hash
$$;

REVOKE ALL ON FUNCTION find_user_access(uuid), find_login_credentials(text), find_invitation(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_user_access(uuid), find_login_credentials(text), find_invitation(text) TO kasa_app;

-- Each record's hash covers the previous record's hash, so changing or removing a past record breaks every
-- link after it. Triggers stop ordinary changes; the chain exposes even those made with superuser rights.
CREATE TABLE audit_log (
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    seq         bigint      NOT NULL,
    created_at  timestamptz NOT NULL,
    actor_type  text        NOT NULL CHECK (actor_type IN ('USER', 'API_KEY', 'SYSTEM')),
    actor_id    text,
    actor_label text,
    action      text        NOT NULL,
    target_type text,
    target_id   text,
    metadata    text        NOT NULL,
    ip          text,
    prev_hash   text        NOT NULL,
    hash        text        NOT NULL,
    PRIMARY KEY (tenant_id, seq)
);

CREATE INDEX audit_log_action_idx ON audit_log (tenant_id, action, seq DESC);

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON audit_log TO kasa_app;
