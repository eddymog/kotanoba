-- Real auth. app_user.password_hash (V1) already exists — nothing seeded it
-- with a real hash until now, since Slice 1 stood in with the hardcoded dev
-- user from V2. That row is now just inert legacy data: its password_hash
-- ('unusable-placeholder-not-a-real-hash') can never match a real Argon2
-- verification, so it simply can't log in. Left alone rather than edited —
-- Flyway migrations already applied to a running database are not meant to
-- be rewritten after the fact.
--
-- Access tokens are short-lived JWTs, verified by signature alone — no table
-- for those, that's the point of a JWT. Refresh tokens are the opposite on
-- purpose: opaque random strings, persisted, so a stolen or logged-out
-- refresh token can actually be revoked. A JWT can't be un-issued once
-- signed; this table is what makes revocation possible at all.
CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,

    -- SHA-256 of the actual token value, never the raw token — same reasoning
    -- as password_hash. A leaked table must not hand out usable credentials.
    token_hash TEXT        NOT NULL UNIQUE,

    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Served by /api/auth/refresh and /api/auth/logout, both looking up "this
-- user's active refresh tokens."
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
