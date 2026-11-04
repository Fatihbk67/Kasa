-- Administrators now sign in on the same page as merchants, in two steps. This holds the proof that the
-- password step passed; it opens nothing but the code step.
CREATE TABLE admin_login_challenges (
    token_hash    text PRIMARY KEY,
    admin_id      uuid        NOT NULL REFERENCES platform_admins (id),
    token_version integer     NOT NULL,
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON admin_login_challenges TO kasa_app;
GRANT UPDATE (used_at) ON admin_login_challenges TO kasa_app;

-- A member asks for a different role. The platform administrator decides, and the decision is kept with it.
CREATE TABLE role_requests (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    user_id        uuid        NOT NULL REFERENCES users (id),
    from_role   text        NOT NULL CHECK (from_role IN ('OWNER', 'ADMIN', 'VIEWER')),
    requested_role text        NOT NULL CHECK (requested_role IN ('OWNER', 'ADMIN', 'VIEWER')),
    reason         text        NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    decided_by     text,
    decision_note  text,
    decided_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT asks_for_a_change CHECK (requested_role <> from_role)
);

-- One open request per person, so a decision always answers the latest ask.
CREATE UNIQUE INDEX role_requests_one_open ON role_requests (user_id) WHERE status = 'PENDING';
CREATE INDEX role_requests_pending_idx ON role_requests (created_at) WHERE status = 'PENDING';

CREATE FUNCTION assert_role_request_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'illegal role request transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER role_requests_valid_transition
    BEFORE UPDATE ON role_requests
    FOR EACH ROW EXECUTE FUNCTION assert_role_request_transition();

ALTER TABLE role_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_requests
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON role_requests TO kasa_app;
GRANT UPDATE (status, decided_by, decision_note, decided_at) ON role_requests TO kasa_app;

-- The platform administrator looks after people in every business, so these are their views across tenants.
-- They return no password hashes or second factor secrets: only what a person on the platform's side may see.
CREATE FUNCTION platform_users(p_query text, p_limit integer)
    RETURNS TABLE (id uuid, email text, role text, disabled boolean, two_factor boolean, created_at timestamptz,
                   tenant_id uuid, tenant_name text, tenant_status text, pending_request text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT u.id, u.email, u.role, u.disabled_at IS NOT NULL, u.totp_enabled_at IS NOT NULL, u.created_at,
       t.id, t.name, t.status,
       (SELECT r.requested_role FROM role_requests r WHERE r.user_id = u.id AND r.status = 'PENDING')
FROM users u
JOIN tenants t ON t.id = u.tenant_id
WHERE p_query IS NULL OR u.email ILIKE '%' || p_query || '%' OR t.name ILIKE '%' || p_query || '%'
ORDER BY u.created_at DESC, u.id
LIMIT p_limit
$$;

CREATE FUNCTION platform_role_requests(p_status text, p_limit integer)
    RETURNS TABLE (id uuid, user_id uuid, email text, tenant_id uuid, tenant_name text, from_role text,
                   requested_role text, reason text, status text, decided_by text, decision_note text,
                   decided_at timestamptz, created_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT r.id, r.user_id, u.email, t.id, t.name, r.from_role, r.requested_role, r.reason, r.status, r.decided_by,
       r.decision_note, r.decided_at, r.created_at
FROM role_requests r
JOIN users u ON u.id = r.user_id
JOIN tenants t ON t.id = r.tenant_id
WHERE p_status IS NULL OR r.status = p_status
ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.created_at DESC
LIMIT p_limit
$$;

-- Which business a person or a request belongs to, so the decision can then run inside that tenant's isolation.
CREATE FUNCTION find_user_tenant(p_user_id uuid)
    RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT tenant_id FROM users WHERE id = p_user_id
$$;

CREATE FUNCTION find_role_request_tenant(p_request_id uuid)
    RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT tenant_id FROM role_requests WHERE id = p_request_id
$$;

REVOKE ALL ON FUNCTION platform_users(text, integer), platform_role_requests(text, integer),
    find_user_tenant(uuid), find_role_request_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_users(text, integer), platform_role_requests(text, integer),
    find_user_tenant(uuid), find_role_request_tenant(uuid) TO kasa_app;
