-- A sign-in session. Its refresh token lives in an HttpOnly cookie, so a page reload can get a new
-- access token without asking for the password again.
CREATE TABLE user_session (
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    user_id        UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Absolute end: after this, sign in again however active the session was
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(20)
);
CREATE INDEX idx_user_session_expires ON user_session (expires_at);

-- Refresh tokens rotate: each is used once and replaced by the next. A used token presented again
-- means it was copied, and ends the whole session. Only the SHA-256 hash is stored, so the table
-- holds nothing an attacker could use.
CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    session_id  UUID         NOT NULL REFERENCES user_session (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX idx_refresh_token_session ON refresh_token (session_id);
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);
