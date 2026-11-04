-- Fraud screening. Each business writes its own rules, because what is normal for a coffee shop is suspicious
-- for a florist. A rule either holds a payment for a person to review or blocks it before the bank is asked.
CREATE TABLE risk_rules (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    kind           text        NOT NULL CHECK (kind IN ('CARD_VELOCITY', 'CARD_DECLINES', 'AMOUNT_ABOVE')),
    -- Attempts or declines for the card rules, an amount in minor units for AMOUNT_ABOVE.
    threshold      bigint      NOT NULL CHECK (threshold > 0),
    window_minutes integer CHECK (window_minutes BETWEEN 1 AND 10080),
    currency       char(3),
    action         text        NOT NULL CHECK (action IN ('REVIEW', 'BLOCK')),
    enabled        boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rule_shape CHECK (
        (kind = 'AMOUNT_ABOVE' AND currency IS NOT NULL AND window_minutes IS NULL)
            OR (kind <> 'AMOUNT_ABOVE' AND window_minutes IS NOT NULL AND currency IS NULL))
);

CREATE INDEX risk_rules_tenant_idx ON risk_rules (tenant_id, created_at);

ALTER TABLE risk_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON risk_rules
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, DELETE ON risk_rules TO kasa_app;
GRANT UPDATE (threshold, window_minutes, currency, action, enabled, updated_at) ON risk_rules TO kasa_app;

-- Cards the business never wants to see again. The card is known only by the fingerprint of its token.
CREATE TABLE risk_blocklist (
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    card_fingerprint text        NOT NULL,
    reason           text        NOT NULL,
    source_payment   uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, card_fingerprint)
);

ALTER TABLE risk_blocklist ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON risk_blocklist
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, DELETE ON risk_blocklist TO kasa_app;

-- What screening decided, kept on the payment so the decision can be explained later.
ALTER TABLE payments
    ADD COLUMN card_fingerprint text,
    ADD COLUMN risk_level       text CHECK (risk_level IN ('NORMAL', 'REVIEW', 'BLOCKED')),
    ADD COLUMN risk_reasons     text[] NOT NULL DEFAULT '{}',
    ADD COLUMN review_status    text CHECK (review_status IN ('OPEN', 'APPROVED', 'REJECTED')),
    ADD COLUMN reviewed_by      text,
    ADD COLUMN reviewed_at      timestamptz;

-- The rules count a card's recent attempts on every payment, so that lookup must stay an index range scan.
CREATE INDEX payments_card_recent_idx ON payments (tenant_id, card_fingerprint, created_at)
    WHERE card_fingerprint IS NOT NULL;
CREATE INDEX payments_review_queue_idx ON payments (tenant_id, created_at) WHERE review_status = 'OPEN';

GRANT UPDATE (review_status, reviewed_by, reviewed_at) ON payments TO kasa_app;

-- The application refuses to capture a held payment; the database refuses too, so no forgotten code path can.
CREATE FUNCTION assert_review_released() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'CAPTURED' AND OLD.status <> 'CAPTURED' AND NEW.review_status IN ('OPEN', 'REJECTED') THEN
        RAISE EXCEPTION 'payment % is held for risk review', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payments_review_released
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION assert_review_released();
