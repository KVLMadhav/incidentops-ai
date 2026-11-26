ALTER TABLE outbox_events ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_events ADD COLUMN lease_owner VARCHAR(36);
ALTER TABLE outbox_events ADD COLUMN last_error VARCHAR(200);
CREATE INDEX ix_outbox_dispatch ON outbox_events(published_at,available_at,lease_until);

CREATE TABLE processed_events (
 consumer_name VARCHAR(80) NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY (consumer_name,event_id)
);
-- The durable business effect and receipt are committed in one SQL transaction.
CREATE TABLE incident_notifications (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id),
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
 event_id VARCHAR(36) NOT NULL UNIQUE,
 event_type VARCHAR(80) NOT NULL,
 message VARCHAR(300) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_notifications_team ON incident_notifications(team_id,created_at DESC,id);
