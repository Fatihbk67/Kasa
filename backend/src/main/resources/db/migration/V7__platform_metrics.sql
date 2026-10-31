-- Counts across all tenants for operational metrics. Aggregates only, so no merchant's data leaves this function.
CREATE FUNCTION platform_backlog_counts()
    RETURNS TABLE (pending_payment_operations bigint, pending_payouts bigint, queued_webhook_deliveries bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT (SELECT count(*) FROM payment_operations WHERE status = 'PENDING'),
       (SELECT count(*) FROM payouts WHERE status = 'PENDING'),
       (SELECT count(*) FROM webhook_deliveries WHERE status = 'PENDING')
$$;

REVOKE ALL ON FUNCTION platform_backlog_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_backlog_counts() TO kasa_app;
