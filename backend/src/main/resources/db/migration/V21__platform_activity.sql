-- The operator's dashboard across all merchants. Like platform_overview, these return aggregates only: totals per
-- day and per merchant, never a single payment, so no merchant's records leave the database through them.

-- One row per local day in the range, days without activity included, so a chart has no gaps.
CREATE FUNCTION platform_daily_activity(p_first date, p_last date, p_currency text)
    RETURNS TABLE (day date, captured bigint, refunded bigint, capture_count bigint, authorization_attempts bigint,
                   authorizations_approved bigint, new_merchants bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
WITH days AS (
    SELECT generate_series(p_first, p_last, interval '1 day')::date AS day
), bounds AS (
    SELECT (p_first::timestamp AT TIME ZONE 'Europe/Istanbul') AS from_at,
           ((p_last + 1)::timestamp AT TIME ZONE 'Europe/Istanbul') AS to_at
), moves AS (
    SELECT (o.updated_at AT TIME ZONE 'Europe/Istanbul')::date AS day, o.type, o.status, o.amount
    FROM payment_operations o
    JOIN payments p ON p.id = o.payment_id, bounds b
    WHERE o.type IN ('CAPTURE', 'REFUND', 'AUTHORIZE') AND o.status IN ('SUCCEEDED', 'FAILED')
      AND p.currency = p_currency AND o.updated_at >= b.from_at AND o.updated_at < b.to_at
), joined AS (
    SELECT (t.created_at AT TIME ZONE 'Europe/Istanbul')::date AS day, count(*) AS merchants
    FROM tenants t, bounds b
    WHERE t.created_at >= b.from_at AND t.created_at < b.to_at
    GROUP BY 1
)
SELECT d.day,
       COALESCE(SUM(m.amount) FILTER (WHERE m.type = 'CAPTURE' AND m.status = 'SUCCEEDED'), 0)::bigint,
       COALESCE(SUM(m.amount) FILTER (WHERE m.type = 'REFUND' AND m.status = 'SUCCEEDED'), 0)::bigint,
       COUNT(m.day) FILTER (WHERE m.type = 'CAPTURE' AND m.status = 'SUCCEEDED'),
       COUNT(m.day) FILTER (WHERE m.type = 'AUTHORIZE'),
       COUNT(m.day) FILTER (WHERE m.type = 'AUTHORIZE' AND m.status = 'SUCCEEDED'),
       COALESCE(MAX(j.merchants), 0)
FROM days d
LEFT JOIN moves m ON m.day = d.day
LEFT JOIN joined j ON j.day = d.day
GROUP BY d.day
ORDER BY d.day
$$;

REVOKE ALL ON FUNCTION platform_daily_activity(date, date, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_daily_activity(date, date, text) TO kasa_app;

-- The merchants that collected the most in a period, for spotting who drives volume (or a sudden outlier).
CREATE FUNCTION platform_top_merchants(p_from timestamptz, p_to timestamptz, p_currency text, p_limit integer)
    RETURNS TABLE (id uuid, name text, status text, captured bigint, capture_count bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT t.id, t.name, t.status, SUM(o.amount)::bigint, COUNT(*)
FROM payment_operations o
JOIN payments p ON p.id = o.payment_id
JOIN tenants t ON t.id = o.tenant_id
WHERE o.type = 'CAPTURE' AND o.status = 'SUCCEEDED' AND p.currency = p_currency
  AND o.updated_at >= p_from AND o.updated_at < p_to
GROUP BY t.id, t.name, t.status
ORDER BY 4 DESC, t.name
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION platform_top_merchants(timestamptz, timestamptz, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_top_merchants(timestamptz, timestamptz, text, integer) TO kasa_app;
