-- The operator's overview across tenants. Aggregates only, so no merchant's data leaves this function.
CREATE FUNCTION platform_overview()
    RETURNS TABLE (tenant_count bigint, payment_count bigint, captures_last_day bigint, failed_payouts bigint,
                   failed_webhook_deliveries bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT (SELECT count(*) FROM tenants),
       (SELECT count(*) FROM payments),
       (SELECT count(*) FROM payment_operations
        WHERE type = 'CAPTURE' AND status = 'SUCCEEDED' AND updated_at >= now() - interval '1 day'),
       (SELECT count(*) FROM payouts WHERE status = 'FAILED'),
       (SELECT count(*) FROM webhook_deliveries WHERE status = 'FAILED')
$$;

REVOKE ALL ON FUNCTION platform_overview() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_overview() TO kasa_app;

-- Serves day-by-day analytics, which read completed operations of one tenant over a time range.
CREATE INDEX payment_operations_tenant_time_idx ON payment_operations (tenant_id, updated_at);
CREATE INDEX payments_tenant_time_idx ON payments (tenant_id, created_at);
