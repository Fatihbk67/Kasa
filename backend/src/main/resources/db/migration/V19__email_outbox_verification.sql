-- Mail leaves through a queue rather than straight from the request. A provider outage or a crash between commit
-- and send then delays an email instead of losing it, and an email written in the same transaction as its event
-- (an account created, a link issued) exists exactly when the event does.
CREATE TABLE email_outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient       text        NOT NULL,
    subject         text        NOT NULL,
    -- Encrypted, and erased once sent: a body can hold a password reset link, which works like a password.
    body            text,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    provider_id     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz
);

CREATE INDEX email_outbox_due_idx ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX email_outbox_created_idx ON email_outbox (created_at);

GRANT SELECT, INSERT, DELETE ON email_outbox TO kasa_app;
GRANT UPDATE (body, status, attempts, next_attempt_at, last_error, provider_id, sent_at) ON email_outbox TO kasa_app;

-- Proof that a person controls the address they signed up with.
ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
-- Accounts from before verification existed are treated as verified, rather than locked out overnight.
UPDATE users SET email_verified_at = created_at;
GRANT UPDATE (email_verified_at) ON users TO kasa_app;

CREATE TABLE email_verification_tokens (
    token_hash text PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    email      text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX email_verification_tokens_user_idx ON email_verification_tokens (user_id) WHERE used_at IS NULL;

ALTER TABLE email_verification_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_verification_tokens
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT ON email_verification_tokens TO kasa_app;
GRANT UPDATE (used_at) ON email_verification_tokens TO kasa_app;

-- Someone opening the link from their mailbox may not be signed in, so this is the link's only way past RLS.
CREATE FUNCTION find_email_verification(p_hash text)
    RETURNS TABLE (tenant_id uuid, user_id uuid, email text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public AS
$$
SELECT v.tenant_id, v.user_id, v.email
FROM email_verification_tokens v
WHERE v.token_hash = p_hash AND v.used_at IS NULL AND v.expires_at > now()
$$;

REVOKE ALL ON FUNCTION find_email_verification(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_email_verification(text) TO kasa_app;
