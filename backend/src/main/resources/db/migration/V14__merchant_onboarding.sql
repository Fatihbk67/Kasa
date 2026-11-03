-- A payment company checks who a merchant is before letting them take money. Businesses that already exist
-- keep working: only new registrations start out waiting for that check.
ALTER TABLE tenants
    ADD COLUMN status         text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    ADD COLUMN status_reason  text,
    ADD COLUMN reviewed_at    timestamptz,
    ADD COLUMN reviewed_by    text;

GRANT UPDATE (status, status_reason, reviewed_at, reviewed_by) ON tenants TO kasa_app;

CREATE FUNCTION assert_tenant_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'PENDING' AND NEW.status IN ('ACTIVE', 'SUSPENDED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'SUSPENDED')
            OR (OLD.status = 'SUSPENDED' AND NEW.status = 'ACTIVE')
        ) THEN
        RAISE EXCEPTION 'illegal merchant transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenants_valid_transition
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION assert_tenant_transition();

-- The platform operator reviews every merchant, so this is their one view across tenants.
CREATE FUNCTION platform_merchants(p_status text, p_limit integer)
    RETURNS TABLE (id uuid, name text, status text, status_reason text, created_at timestamptz, reviewed_at timestamptz,
                   reviewed_by text, owner_email text, member_count bigint, payment_count bigint, captured bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT t.id, t.name, t.status, t.status_reason, t.created_at, t.reviewed_at, t.reviewed_by,
       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY u.created_at LIMIT 1),
       (SELECT COUNT(*) FROM users u WHERE u.tenant_id = t.id AND u.disabled_at IS NULL),
       (SELECT COUNT(*) FROM payments p WHERE p.tenant_id = t.id),
       (SELECT COALESCE(SUM(p.captured_amount), 0)::bigint FROM payments p WHERE p.tenant_id = t.id)
FROM tenants t
WHERE p_status IS NULL OR t.status = p_status
ORDER BY CASE t.status WHEN 'PENDING' THEN 0 ELSE 1 END, t.created_at DESC
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION platform_merchants(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_merchants(text, integer) TO kasa_app;

-- A suspended merchant is not paid out, and no merchant short of active is charged on a schedule.
CREATE OR REPLACE FUNCTION find_unsettled_tenants(p_cutoff timestamptz)
    RETURNS TABLE (tenant_id uuid, currency text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT DISTINCT o.tenant_id, p.currency::text
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
JOIN tenants t ON t.id = o.tenant_id
WHERE o.status = 'SUCCEEDED'
  AND o.type IN ('CAPTURE', 'REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE')
  AND o.updated_at < p_cutoff
  AND t.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM payout_items i WHERE i.operation_id = o.id AND i.released_at IS NULL)
$$;

CREATE OR REPLACE FUNCTION find_subscriptions_to_renew(p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT s.id, s.tenant_id
FROM subscriptions s
JOIN tenants t ON t.id = s.tenant_id
WHERE s.status = 'ACTIVE' AND s.current_period_end <= now() AND t.status = 'ACTIVE'
ORDER BY s.current_period_end
LIMIT p_limit
$$;

CREATE OR REPLACE FUNCTION find_invoices_to_charge(p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT i.id, i.tenant_id
FROM invoices i
JOIN tenants t ON t.id = i.tenant_id
WHERE i.status = 'OPEN' AND i.next_attempt_at <= now() AND t.status = 'ACTIVE'
ORDER BY i.next_attempt_at
LIMIT p_limit
$$;
