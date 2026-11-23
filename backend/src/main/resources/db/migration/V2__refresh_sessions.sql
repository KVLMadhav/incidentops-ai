CREATE TABLE auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_sessions_user ON auth_sessions(user_id);
CREATE INDEX ix_sessions_expiry ON auth_sessions(expires_at);
CREATE TABLE refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_refresh_session ON refresh_tokens(session_id);
