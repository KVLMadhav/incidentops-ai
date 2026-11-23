ALTER TABLE users ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE auth_sessions ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE refresh_tokens ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE refresh_tokens ADD COLUMN consumed_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE teams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_teams_name_key UNIQUE (name_key),
    CONSTRAINT ck_teams_name_key_normalized CHECK (name_key = LOWER(TRIM(name_key)))
);

CREATE TABLE team_members (
    id VARCHAR(36) PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_team_members_team_user UNIQUE (team_id, user_id),
    CONSTRAINT ck_team_members_role CHECK (role IN ('ADMIN', 'INCIDENT_MANAGER', 'RESPONDER', 'VIEWER'))
);
CREATE INDEX ix_team_members_user_team ON team_members(user_id, team_id);
CREATE INDEX ix_team_members_team_role ON team_members(team_id, role);

CREATE TABLE monitored_services (
    id VARCHAR(36) PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    description VARCHAR(2000),
    owner_user_id VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    external_identifier VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_services_team_name UNIQUE (team_id, name_key),
    CONSTRAINT uk_services_team_external UNIQUE (team_id, external_identifier),
    CONSTRAINT ck_services_name_key_normalized CHECK (name_key = LOWER(TRIM(name_key)))
);
CREATE INDEX ix_services_team_active_name ON monitored_services(team_id, active, name_key, id);
CREATE INDEX ix_services_team_owner ON monitored_services(team_id, owner_user_id);
