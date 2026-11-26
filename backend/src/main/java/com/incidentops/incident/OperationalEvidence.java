package com.incidentops.incident;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;

/** Called inside the same transaction as the fact. No sensitive payloads in evidence. */
@Component
public class OperationalEvidence {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OperationalEvidence(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void record(String incident,String type,String detail,String correlation){
        var now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO incident_operational_timeline(id,incident_id,type,detail,correlation_id,created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID().toString(),incident,type,detail,correlation,now);
        // Preserve the existing required actor FK; detail explicitly identifies the system action.
        jdbc.update("""
            INSERT INTO audit_logs(id,team_id,incident_id,actor_user_id,action,detail,created_at,correlation_id)
            SELECT ?,team_id,id,creator_user_id,?,?,?,? FROM incidents WHERE id=?
            """,UUID.randomUUID().toString(),type,"System: "+detail+" correlation="+correlation,now,correlation,incident);
    }
}
