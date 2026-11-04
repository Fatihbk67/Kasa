-- What a payment company needs to know before letting a business take money: who it legally is, how it is
-- taxed, and where its money goes. The IBAN is encrypted; only its last digits and bank code are stored plainly.
CREATE TABLE merchant_profiles (
    tenant_id     uuid PRIMARY KEY REFERENCES tenants (id),
    business_type text        NOT NULL CHECK (business_type IN ('INDIVIDUAL', 'SOLE_PROPRIETOR', 'COMPANY')),
    legal_name    text        NOT NULL,
    tax_id        text        NOT NULL,
    tax_office    text        NOT NULL,
    sector        text        NOT NULL CHECK (sector IN ('ECOMMERCE', 'FOOD', 'EDUCATION', 'SERVICES', 'RETAIL', 'SOFTWARE', 'OTHER')),
    website       text,
    phone         text        NOT NULL,
    address       text        NOT NULL,
    city          text        NOT NULL,
    iban          text        NOT NULL,
    iban_last4    char(4)     NOT NULL,
    bank_code     char(5)     NOT NULL,
    submitted_at  timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- A company has a 10 digit tax number; a person, alone or as a sole trader, is taxed under their 11 digit id.
    CONSTRAINT tax_id_matches_type CHECK (
        (business_type = 'COMPANY' AND tax_id ~ '^\d{10}$') OR (business_type <> 'COMPANY' AND tax_id ~ '^\d{11}$'))
);

ALTER TABLE merchant_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON merchant_profiles
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON merchant_profiles TO kasa_app;
GRANT UPDATE (business_type, legal_name, tax_id, tax_office, sector, website, phone, address, city, iban, iban_last4,
              bank_code, submitted_at, updated_at) ON merchant_profiles TO kasa_app;

-- The reviewer reads one application at a time, across tenants.
CREATE FUNCTION find_merchant_profile(p_tenant_id uuid)
    RETURNS SETOF merchant_profiles
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT * FROM merchant_profiles WHERE tenant_id = p_tenant_id
$$;

REVOKE ALL ON FUNCTION find_merchant_profile(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_merchant_profile(uuid) TO kasa_app;

-- The review list shows whether an application has been sent, so approve is never offered for an empty one.
DROP FUNCTION platform_merchants(text, integer);

CREATE FUNCTION platform_merchants(p_status text, p_limit integer)
    RETURNS TABLE (id uuid, name text, status text, status_reason text, created_at timestamptz, reviewed_at timestamptz,
                   reviewed_by text, owner_email text, member_count bigint, payment_count bigint, captured bigint,
                   profile_submitted_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT t.id, t.name, t.status, t.status_reason, t.created_at, t.reviewed_at, t.reviewed_by,
       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY u.created_at LIMIT 1),
       (SELECT COUNT(*) FROM users u WHERE u.tenant_id = t.id AND u.disabled_at IS NULL),
       (SELECT COUNT(*) FROM payments p WHERE p.tenant_id = t.id),
       (SELECT COALESCE(SUM(p.captured_amount), 0)::bigint FROM payments p WHERE p.tenant_id = t.id),
       (SELECT m.submitted_at FROM merchant_profiles m WHERE m.tenant_id = t.id)
FROM tenants t
WHERE p_status IS NULL OR t.status = p_status
ORDER BY CASE t.status WHEN 'PENDING' THEN 0 ELSE 1 END, t.created_at DESC
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION platform_merchants(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_merchants(text, integer) TO kasa_app;

-- One row per person, written when the event happens, so each member keeps their own read state and a person
-- who joins later is not shown what happened before they arrived.
CREATE TABLE notifications (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL,
    title      text        NOT NULL,
    body       text,
    link       text,
    read_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_inbox_idx ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE read_at IS NULL;

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notifications
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON notifications TO kasa_app;
GRANT UPDATE (read_at) ON notifications TO kasa_app;
