CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = LOWER(TRIM(email))),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'INCIDENT_MANAGER', 'RESPONDER', 'VIEWER'))
);
CREATE INDEX ix_users_created_at_id ON users (created_at, id);
