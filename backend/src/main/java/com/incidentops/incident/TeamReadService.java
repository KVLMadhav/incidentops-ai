package com.incidentops.incident;

import com.incidentops.identity.team.TeamService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class TeamReadService {
    public record Summary(long total,long active,long breachedTargets,Instant generatedAt){}
    public record Notification(String id,String incidentId,String eventType,String message,Instant createdAt,
                               String target,String deliveryId,String channel,String status,int attempts,String lastError){}
    private final TeamService teams;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ReliabilityCache cache;
    public TeamReadService(TeamService teams,JdbcTemplate jdbc,Clock clock,ReliabilityCache cache) {
        this.teams=teams;this.jdbc=jdbc;this.clock=clock;this.cache=cache;
    }
    public Summary summary(String actor,String team){
        teams.requireMembership(actor,team); // Always consult SQL before touching cache.
        String key="incidentops:v1:team:"+team+":summary";
        var cached=cache.get(key,Summary.class);if(cached!=null)return cached;
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM incidents WHERE team_id=?",Long.class,team);
        long active=jdbc.queryForObject("SELECT COUNT(*) FROM incidents WHERE team_id=? AND status NOT IN ('RESOLVED','CLOSED')",Long.class,team);
        long breached=jdbc.queryForObject("""
            SELECT COUNT(*) FROM incident_sla_targets s JOIN incidents i ON i.id=s.incident_id
            WHERE i.team_id=? AND s.due_at<? AND (s.completed_at IS NULL OR s.completed_at>s.due_at)
            """,Long.class,team,java.sql.Timestamp.from(clock.instant()));
        var result=new Summary(total,active,breached,clock.instant());
        cache.putAfterCommit(key,result);
        return result;
    }
    public List<Notification> notifications(String actor,String team){
        teams.requireMembership(actor,team);
        return jdbc.query("""
            SELECT n.*,d.id AS delivery_id,d.channel,d.status,d.attempts,d.last_error
            FROM incident_notifications n LEFT JOIN notification_deliveries d ON d.notification_id=n.id
            WHERE n.team_id=? ORDER BY n.created_at DESC,n.id LIMIT 50
            """,(rs,n)->new Notification(rs.getString("id"),rs.getString("incident_id"),rs.getString("event_type"),
                rs.getString("message"),rs.getTimestamp("created_at").toInstant(),rs.getString("target"),
                rs.getString("delivery_id"),rs.getString("channel"),rs.getString("status"),rs.getInt("attempts"),rs.getString("last_error")),team);
    }
}
