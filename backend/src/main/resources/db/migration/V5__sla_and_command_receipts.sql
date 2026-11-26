-- Additive upgrade: V1-V4 remain unchanged for existing installations.
CREATE TABLE incident_sla_targets (
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
 target VARCHAR(20) NOT NULL CHECK (target IN ('ACKNOWLEDGE','MITIGATE','RESOLVE')),
 policy_version VARCHAR(40) NOT NULL,
 due_at TIMESTAMP WITH TIME ZONE NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE,
 breached_at TIMESTAMP WITH TIME ZONE,
 PRIMARY KEY (incident_id, target)
);
CREATE INDEX ix_sla_pending ON incident_sla_targets(breached_at, due_at, incident_id);

CREATE TABLE incident_command_receipts (
 incident_id VARCHAR(36) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
 command_id VARCHAR(36) NOT NULL,
 actor_user_id VARCHAR(36) NOT NULL REFERENCES users(id),
 request_hash VARCHAR(64) NOT NULL,
 response_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY (incident_id, command_id)
);

-- Backfill existing incidents, preserving their actual historical milestone times.
INSERT INTO incident_sla_targets(incident_id,target,policy_version,due_at,completed_at)
SELECT i.id,'ACKNOWLEDGE','v1',i.created_at + CASE i.severity
 WHEN 'SEV1' THEN INTERVAL '5' MINUTE WHEN 'SEV2' THEN INTERVAL '15' MINUTE
 WHEN 'SEV3' THEN INTERVAL '60' MINUTE ELSE INTERVAL '240' MINUTE END,
 (SELECT MIN(h.changed_at) FROM incident_status_history h WHERE h.incident_id=i.id AND h.new_status='ACKNOWLEDGED')
FROM incidents i;
INSERT INTO incident_sla_targets(incident_id,target,policy_version,due_at,completed_at)
SELECT i.id,'MITIGATE','v1',i.created_at + CASE i.severity
 WHEN 'SEV1' THEN INTERVAL '30' MINUTE WHEN 'SEV2' THEN INTERVAL '120' MINUTE
 WHEN 'SEV3' THEN INTERVAL '480' MINUTE ELSE INTERVAL '1440' MINUTE END,
 (SELECT MIN(h.changed_at) FROM incident_status_history h WHERE h.incident_id=i.id AND h.new_status='MITIGATED')
FROM incidents i;
INSERT INTO incident_sla_targets(incident_id,target,policy_version,due_at,completed_at)
SELECT i.id,'RESOLVE','v1',i.created_at + CASE i.severity
 WHEN 'SEV1' THEN INTERVAL '240' MINUTE WHEN 'SEV2' THEN INTERVAL '480' MINUTE
 WHEN 'SEV3' THEN INTERVAL '1440' MINUTE ELSE INTERVAL '2880' MINUTE END,
 (SELECT MIN(h.changed_at) FROM incident_status_history h WHERE h.incident_id=i.id AND h.new_status='RESOLVED')
FROM incidents i;
