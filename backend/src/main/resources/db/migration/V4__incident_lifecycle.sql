CREATE SEQUENCE incident_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE alerts (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 service_id VARCHAR(36) NOT NULL REFERENCES monitored_services(id),
 source VARCHAR(100) NOT NULL,
 external_alert_id VARCHAR(150) NOT NULL,
 title VARCHAR(200) NOT NULL,
 description VARCHAR(4000),
 severity VARCHAR(4) NOT NULL CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 metadata VARCHAR(4000) NOT NULL,
 CONSTRAINT uk_alert_source_external UNIQUE (team_id, source, external_alert_id)
);
CREATE INDEX ix_alert_team_received ON alerts(team_id, received_at DESC, id);

CREATE TABLE incidents (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 service_id VARCHAR(36) NOT NULL REFERENCES monitored_services(id),
 incident_number VARCHAR(24) NOT NULL UNIQUE,
 title VARCHAR(200) NOT NULL,
 description VARCHAR(4000),
 severity VARCHAR(4) NOT NULL CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
 status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED','REOPENED')),
 version BIGINT NOT NULL,
 creator_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 assignee_user_id VARCHAR(36) REFERENCES users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_incident_team_created ON incidents(team_id, created_at DESC, id);
CREATE INDEX ix_incident_team_filter ON incidents(team_id, status, severity, service_id, assignee_user_id);

CREATE TABLE incident_alerts (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 alert_id VARCHAR(36) NOT NULL REFERENCES alerts(id),
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uk_incident_alert UNIQUE (incident_id, alert_id)
);
CREATE INDEX ix_incident_alerts_incident ON incident_alerts(incident_id, linked_at, id);

CREATE TABLE incident_assignments (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 assignee_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 previous_assignee_user_id VARCHAR(36) REFERENCES users(id),
 reason VARCHAR(1000),
 assigned_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_incident_assignments_incident ON incident_assignments(incident_id, assigned_at, id);

CREATE TABLE incident_notes (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 body VARCHAR(4000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_incident_notes_incident ON incident_notes(incident_id, created_at, id);

CREATE TABLE incident_status_history (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 old_status VARCHAR(20),
 new_status VARCHAR(20) NOT NULL,
 reason VARCHAR(1000),
 changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_incident_history_incident ON incident_status_history(incident_id, changed_at, id);

CREATE TABLE audit_logs (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 action VARCHAR(40) NOT NULL,
 detail VARCHAR(1000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_audit_incident ON audit_logs(incident_id, created_at, id);

CREATE TABLE outbox_events (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 aggregate_id VARCHAR(36) NOT NULL REFERENCES incidents(id),
 event_type VARCHAR(80) NOT NULL,
 payload VARCHAR(4000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_outbox_pending ON outbox_events(published_at, created_at, id);
