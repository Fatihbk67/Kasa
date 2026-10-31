CREATE TABLE webhook_endpoints (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    url         text        NOT NULL,
    secret      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    disabled_at timestamptz,
    UNIQUE (id, tenant_id)
);

CREATE INDEX webhook_endpoints_tenant_idx ON webhook_endpoints (tenant_id);

-- The payload is text, not jsonb, so that every retry sends and signs exactly the same bytes.
CREATE TABLE webhook_events (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    type       text        NOT NULL,
    payload    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

-- The transactional outbox: rows commit together with the payment change that caused them.
CREATE TABLE webhook_deliveries (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL,
    event_id         uuid        NOT NULL,
    endpoint_id      uuid        NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_status_code integer,
    last_error       text,
    delivered_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (event_id, tenant_id) REFERENCES webhook_events (id, tenant_id),
    FOREIGN KEY (endpoint_id, tenant_id) REFERENCES webhook_endpoints (id, tenant_id),
    UNIQUE (event_id, endpoint_id)
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_deliveries_endpoint_idx ON webhook_deliveries (endpoint_id, created_at DESC);

ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_endpoints
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE webhook_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_events
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_deliveries
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON webhook_endpoints, webhook_events, webhook_deliveries TO kasa_app;
GRANT UPDATE (disabled_at) ON webhook_endpoints TO kasa_app;
GRANT UPDATE (status, attempts, next_attempt_at, last_status_code, last_error, delivered_at, updated_at)
    ON webhook_deliveries TO kasa_app;

-- Claims work across tenants. SKIP LOCKED lets dispatchers on several instances take different rows
-- without waiting. The claim commits before any HTTP call and pushes next_attempt_at forward as a lease,
-- so a dispatcher that crashes mid-send simply has its deliveries picked up again when the lease expires.
CREATE FUNCTION claim_due_webhook_deliveries(p_limit integer, p_lease_seconds bigint)
    RETURNS TABLE (id uuid, tenant_id uuid, attempts integer, url text, secret text,
                   event_id uuid, event_type text, payload text)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
WITH due AS (
    SELECT d.id
    FROM webhook_deliveries d
    WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
    ORDER BY d.next_attempt_at
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED
), claimed AS (
    UPDATE webhook_deliveries d
    SET attempts = d.attempts + 1,
        next_attempt_at = now() + make_interval(secs => p_lease_seconds),
        updated_at = now()
    FROM due
    WHERE d.id = due.id
    RETURNING d.id, d.tenant_id, d.attempts, d.endpoint_id, d.event_id
)
SELECT c.id, c.tenant_id, c.attempts, e.url, e.secret, ev.id, ev.type, ev.payload
FROM claimed c
JOIN webhook_endpoints e ON e.id = c.endpoint_id
JOIN webhook_events ev ON ev.id = c.event_id
$$;

REVOKE ALL ON FUNCTION claim_due_webhook_deliveries(integer, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_due_webhook_deliveries(integer, bigint) TO kasa_app;
