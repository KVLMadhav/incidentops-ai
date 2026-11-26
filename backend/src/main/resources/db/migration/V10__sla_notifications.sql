-- Durations are elapsed seconds; every timestamp is an instant (UTC).
CREATE TABLE sla_policy_locks (lock_slot INTEGER PRIMARY KEY);
INSERT INTO sla_policy_locks SELECT lock_slot FROM alert_ingestion_locks;
CREATE TABLE sla_policies (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
 name VARCHAR(100) NOT NULL,
 severity VARCHAR(4) NOT NULL CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
 acknowledgement_seconds BIGINT NOT NULL CHECK (acknowledgement_seconds > 0),
 resolution_seconds BIGINT NOT NULL CHECK (resolution_seconds >= acknowledgement_seconds),
 active BOOLEAN NOT NULL,
 legacy BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL CHECK (version > 0),
 effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(team_id,severity,version)
);
CREATE INDEX ix_policy_selection ON sla_policies(team_id,severity,version DESC);
-- Archive the pre-Day-4 hard-coded policy so old instances also have a real FK.
INSERT INTO sla_policies(id,team_id,name,severity,acknowledgement_seconds,resolution_seconds,active,legacy,version,effective_at)
 SELECT MIN(id),team_id,'Legacy v1',severity,
 CASE severity WHEN 'SEV1' THEN 300 WHEN 'SEV2' THEN 900 WHEN 'SEV3' THEN 3600 ELSE 14400 END,
 CASE severity WHEN 'SEV1' THEN 14400 WHEN 'SEV2' THEN 28800 WHEN 'SEV3' THEN 86400 ELSE 172800 END,
 FALSE,TRUE,1,MIN(created_at) FROM incidents GROUP BY team_id,severity;
CREATE TABLE sla_instances (
 id VARCHAR(36) PRIMARY KEY,
 incident_id VARCHAR(36) NOT NULL UNIQUE REFERENCES incidents(id) ON DELETE CASCADE,
 policy_id VARCHAR(36) NOT NULL REFERENCES sla_policies(id),
 policy_version VARCHAR(40) NOT NULL,
 severity VARCHAR(4) NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 correlation_id VARCHAR(100) NOT NULL
);
-- Legacy v1 deadline snapshots remain intact; no historical deadline is recalculated.
INSERT INTO sla_instances(id,incident_id,policy_id,policy_version,severity,started_at,correlation_id)
 SELECT i.id,i.id,p.id,'legacy-v1',i.severity,i.created_at,i.id FROM incidents i
 JOIN sla_policies p ON p.team_id=i.team_id AND p.severity=i.severity AND p.legacy=TRUE;
ALTER TABLE incident_sla_targets ADD COLUMN pending BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE incident_sla_targets SET pending=FALSE
 WHERE breached_at IS NOT NULL OR (completed_at IS NOT NULL AND completed_at <= due_at);
CREATE INDEX ix_sla_due_work ON incident_sla_targets(pending,due_at,incident_id,target);
CREATE TABLE sla_breaches (
 id VARCHAR(36) PRIMARY KEY,
 instance_id VARCHAR(36) NOT NULL REFERENCES sla_instances(id) ON DELETE CASCADE,
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
 target VARCHAR(20) NOT NULL,
 due_at TIMESTAMP WITH TIME ZONE NOT NULL,
 detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
 event_id VARCHAR(36) NOT NULL UNIQUE,
 correlation_id VARCHAR(100) NOT NULL,
 UNIQUE(instance_id,target)
);
ALTER TABLE outbox_events ADD COLUMN notification_target VARCHAR(100);
ALTER TABLE outbox_events ADD COLUMN notification_message VARCHAR(300);
ALTER TABLE incident_notifications ADD COLUMN target VARCHAR(100) NOT NULL DEFAULT 'TEAM';
ALTER TABLE incident_notifications ADD COLUMN correlation_id VARCHAR(100);
CREATE TABLE notification_deliveries (
 id VARCHAR(36) PRIMARY KEY,
 notification_id VARCHAR(36) NOT NULL REFERENCES incident_notifications(id) ON DELETE CASCADE,
 channel VARCHAR(30) NOT NULL CHECK (channel='IN_APP'),
 target VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','RETRY','DELIVERED','DEAD_LETTER')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 available_at TIMESTAMP WITH TIME ZONE NOT NULL,
 lease_until TIMESTAMP WITH TIME ZONE,
 lease_owner VARCHAR(36),
 completed_at TIMESTAMP WITH TIME ZONE,
 last_error VARCHAR(200),
 UNIQUE(notification_id,channel,target)
);
CREATE INDEX ix_delivery_due ON notification_deliveries(status,available_at,lease_until);
CREATE TABLE notification_delivery_attempts (
 delivery_id VARCHAR(36) NOT NULL REFERENCES notification_deliveries(id) ON DELETE CASCADE,
 attempt INTEGER NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finished_at TIMESTAMP WITH TIME ZONE,
 status VARCHAR(20) NOT NULL,
 error VARCHAR(200),
 PRIMARY KEY(delivery_id,attempt)
);
CREATE TABLE incident_operational_timeline (
 id VARCHAR(36) PRIMARY KEY,
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
 type VARCHAR(40) NOT NULL,
 detail VARCHAR(1000) NOT NULL,
 correlation_id VARCHAR(100) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_operational_timeline ON incident_operational_timeline(incident_id,created_at,id);
CREATE TABLE sla_scheduler_failures (
 id VARCHAR(36) PRIMARY KEY,
 correlation_id VARCHAR(100) NOT NULL,
 error VARCHAR(200) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE audit_logs ADD COLUMN correlation_id VARCHAR(100);
