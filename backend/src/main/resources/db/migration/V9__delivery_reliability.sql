-- Dedicated lock stripes avoid locking a foreign-key parent while waiting for an incident.
-- Every instance hashes a team to the same stripe. No business FK points at these rows.
CREATE TABLE alert_ingestion_locks (lock_slot INTEGER PRIMARY KEY CHECK (lock_slot BETWEEN 0 AND 255));
INSERT INTO alert_ingestion_locks(lock_slot) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31),(32),(33),(34),(35),(36),(37),(38),(39),(40),(41),(42),(43),(44),(45),(46),(47),(48),(49),(50),(51),(52),(53),(54),(55),(56),(57),(58),(59),(60),(61),(62),(63),(64),(65),(66),(67),(68),(69),(70),(71),(72),(73),(74),(75),(76),(77),(78),(79),(80),(81),(82),(83),(84),(85),(86),(87),(88),(89),(90),(91),(92),(93),(94),(95),(96),(97),(98),(99),(100),(101),(102),(103),(104),(105),(106),(107),(108),(109),(110),(111),(112),(113),(114),(115),(116),(117),(118),(119),(120),(121),(122),(123),(124),(125),(126),(127),(128),(129),(130),(131),(132),(133),(134),(135),(136),(137),(138),(139),(140),(141),(142),(143),(144),(145),(146),(147),(148),(149),(150),(151),(152),(153),(154),(155),(156),(157),(158),(159),(160),(161),(162),(163),(164),(165),(166),(167),(168),(169),(170),(171),(172),(173),(174),(175),(176),(177),(178),(179),(180),(181),(182),(183),(184),(185),(186),(187),(188),(189),(190),(191),(192),(193),(194),(195),(196),(197),(198),(199),(200),(201),(202),(203),(204),(205),(206),(207),(208),(209),(210),(211),(212),(213),(214),(215),(216),(217),(218),(219),(220),(221),(222),(223),(224),(225),(226),(227),(228),(229),(230),(231),(232),(233),(234),(235),(236),(237),(238),(239),(240),(241),(242),(243),(244),(245),(246),(247),(248),(249),(250),(251),(252),(253),(254),(255);
-- Preserve V4's UNIQUE(team_id,source,external_alert_id): fingerprints never identify alerts.
ALTER TABLE alerts ADD COLUMN grouping_fingerprint VARCHAR(64);
CREATE INDEX ix_alert_grouping ON alerts(team_id,service_id,grouping_fingerprint);
CREATE TABLE alert_retry_conflicts (
 id VARCHAR(36) PRIMARY KEY,
 team_id VARCHAR(36) NOT NULL,
 alert_id VARCHAR(36) NOT NULL,
 actor_user_id VARCHAR(36) NOT NULL,
 differing_fields VARCHAR(300) NOT NULL,
 correlation_id VARCHAR(100) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_alert_conflict_team ON alert_retry_conflicts(team_id,created_at);
ALTER TABLE outbox_events ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(100);
CREATE INDEX ix_outbox_due_v9 ON outbox_events(dead_lettered_at,published_at,available_at,lease_until);
CREATE TABLE outbox_replays (
 id VARCHAR(36) PRIMARY KEY,
 event_id VARCHAR(36) NOT NULL,
 actor_user_id VARCHAR(36) NOT NULL,
 team_id VARCHAR(36) NOT NULL,
 reason VARCHAR(300) NOT NULL,
 previous_attempts INTEGER NOT NULL,
 previous_error VARCHAR(200),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
