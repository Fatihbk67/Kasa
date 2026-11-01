-- A plan's price never changes: a new price is a new plan, so an old invoice always explains itself.
CREATE TABLE plans (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    name             text        NOT NULL,
    amount           bigint      NOT NULL CHECK (amount > 0),
    currency         char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    billing_interval text        NOT NULL CHECK (billing_interval IN ('MONTH', 'YEAR')),
    archived_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE INDEX plans_listing_idx ON plans (tenant_id, created_at DESC, id DESC);

-- billing_anchor and period_index together give every period's end, so a subscription started on 31 January
-- bills on 28 February and then on 31 March, instead of drifting to the 28th for good.
CREATE TABLE subscriptions (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenants (id),
    customer_id          uuid        NOT NULL,
    plan_id              uuid        NOT NULL,
    card_token           text        NOT NULL,
    status               text        NOT NULL CHECK (status IN ('INCOMPLETE', 'ACTIVE', 'PAST_DUE', 'CANCELED')),
    billing_anchor       timestamptz NOT NULL,
    period_index         integer     NOT NULL CHECK (period_index > 0),
    current_period_start timestamptz NOT NULL,
    current_period_end   timestamptz NOT NULL,
    cancel_at_period_end boolean     NOT NULL DEFAULT false,
    credit_balance       bigint      NOT NULL DEFAULT 0 CHECK (credit_balance >= 0),
    cancel_reason        text,
    canceled_at          timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id),
    FOREIGN KEY (plan_id, tenant_id) REFERENCES plans (id, tenant_id),
    CONSTRAINT period_moves_forward CHECK (current_period_end > current_period_start)
);

CREATE INDEX subscriptions_listing_idx ON subscriptions (tenant_id, created_at DESC, id DESC);
CREATE INDEX subscriptions_customer_idx ON subscriptions (customer_id);
CREATE INDEX subscriptions_due_idx ON subscriptions (current_period_end) WHERE status = 'ACTIVE';

-- A double click on subscribe finds the first subscription here instead of billing the customer twice.
CREATE UNIQUE INDEX subscriptions_one_live_per_plan ON subscriptions (customer_id, plan_id) WHERE status <> 'CANCELED';

CREATE FUNCTION assert_subscription_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'INCOMPLETE' AND NEW.status IN ('ACTIVE', 'CANCELED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('PAST_DUE', 'CANCELED'))
            OR (OLD.status = 'PAST_DUE' AND NEW.status IN ('ACTIVE', 'CANCELED'))
        ) THEN
        RAISE EXCEPTION 'illegal subscription transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER subscriptions_valid_transition
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION assert_subscription_transition();

CREATE TABLE invoices (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    subscription_id   uuid        NOT NULL,
    kind              text        NOT NULL CHECK (kind IN ('FIRST', 'RENEWAL', 'PLAN_CHANGE')),
    status            text        NOT NULL CHECK (status IN ('OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE')),
    period_start      timestamptz NOT NULL,
    period_end        timestamptz NOT NULL,
    subtotal          bigint      NOT NULL,
    credit_applied    bigint      NOT NULL DEFAULT 0 CHECK (credit_applied >= 0),
    amount            bigint      NOT NULL CHECK (amount >= 0),
    currency          char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description       text        NOT NULL,
    attempt_count     integer     NOT NULL DEFAULT 0,
    attempt_token     text,
    attempt_in_flight boolean     NOT NULL DEFAULT false,
    next_attempt_at   timestamptz,
    payment_id        uuid,
    failure_code      text,
    paid_at           timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (subscription_id, tenant_id) REFERENCES subscriptions (id, tenant_id),
    FOREIGN KEY (payment_id, tenant_id) REFERENCES payments (id, tenant_id),
    CONSTRAINT amount_is_subtotal_less_credit CHECK (amount = GREATEST(subtotal - credit_applied, 0))
);

CREATE INDEX invoices_subscription_idx ON invoices (subscription_id, created_at DESC);
CREATE INDEX invoices_due_idx ON invoices (next_attempt_at) WHERE status = 'OPEN';

-- The rule that stops a period being billed twice, however many jobs run at once.
CREATE UNIQUE INDEX invoices_one_per_period ON invoices (subscription_id, period_start) WHERE kind <> 'PLAN_CHANGE';

CREATE FUNCTION assert_invoice_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> OLD.status AND OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'illegal invoice transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invoices_valid_transition
    BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION assert_invoice_transition();

CREATE TABLE invoice_lines (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid   NOT NULL,
    invoice_id uuid   NOT NULL,
    position   integer NOT NULL,
    kind       text   NOT NULL CHECK (kind IN ('PLAN', 'UNUSED_TIME', 'REMAINING_TIME')),
    plan_name  text   NOT NULL,
    amount     bigint NOT NULL,
    FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices (id, tenant_id),
    UNIQUE (invoice_id, position)
);

ALTER TABLE plans ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plans
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscriptions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoices
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE invoice_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_lines
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON plans, subscriptions, invoices, invoice_lines TO kasa_app;
GRANT UPDATE (archived_at) ON plans TO kasa_app;
GRANT UPDATE (plan_id, card_token, status, period_index, current_period_start, current_period_end,
              cancel_at_period_end, credit_balance, cancel_reason, canceled_at, updated_at) ON subscriptions TO kasa_app;
GRANT UPDATE (status, attempt_count, attempt_token, attempt_in_flight, next_attempt_at, payment_id, failure_code,
              paid_at, updated_at) ON invoices TO kasa_app;

-- The billing job serves all tenants, so it needs these two narrow views past RLS.
CREATE FUNCTION find_subscriptions_to_renew(p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT s.id, s.tenant_id
FROM subscriptions s
WHERE s.status = 'ACTIVE' AND s.current_period_end <= now()
ORDER BY s.current_period_end
LIMIT p_limit
$$;

CREATE FUNCTION find_invoices_to_charge(p_limit integer)
    RETURNS TABLE (id uuid, tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT i.id, i.tenant_id
FROM invoices i
WHERE i.status = 'OPEN' AND i.next_attempt_at <= now()
ORDER BY i.next_attempt_at
LIMIT p_limit
$$;

REVOKE ALL ON FUNCTION find_subscriptions_to_renew(integer), find_invoices_to_charge(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_subscriptions_to_renew(integer), find_invoices_to_charge(integer) TO kasa_app;
