package com.incidentops.events;

import com.incidentops.entity.Role;
import com.incidentops.identity.team.TeamService;
import com.incidentops.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;

/** Team operators can inspect durable failures and request an audited replay of the same event ID. */
@RestController
@RequestMapping("/api/v1/teams/{team}/delivery")
public class DeliveryOperations {
    private final TeamService teams;private final JdbcTemplate jdbc;private final Clock clock;
    public DeliveryOperations(TeamService teams,JdbcTemplate jdbc,Clock clock){this.teams=teams;this.jdbc=jdbc;this.clock=clock;}
    public record ReplayRequest(@NotBlank @Size(max=300) String reason) {}
    @GetMapping("/dead-letters")
    public List<Map<String,Object>> deadLetters(Authentication auth,@PathVariable String team) {
        authorize(auth,team);
        return jdbc.queryForList("""
            SELECT id,event_type,aggregate_id,attempts,last_error,dead_lettered_at,created_at
            FROM outbox_events WHERE team_id=? AND dead_lettered_at IS NOT NULL ORDER BY dead_lettered_at,id LIMIT 50
            """,team);
    }
    @GetMapping("/alert-conflicts")
    public List<Map<String,Object>> conflicts(Authentication auth,@PathVariable String team) {
        authorize(auth,team);
        return jdbc.queryForList("SELECT * FROM alert_retry_conflicts WHERE team_id=? ORDER BY created_at DESC,id LIMIT 50",team);
    }
    @PostMapping("/outbox/{id}/replay")
    @Transactional
    public Map<String,String> replay(Authentication auth,@PathVariable String team,@PathVariable String id,@Valid @RequestBody ReplayRequest request) {
        authorize(auth,team);
        var rows=jdbc.queryForList("SELECT * FROM outbox_events WHERE team_id=? AND id=? FOR UPDATE",team,id);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"EVENT_NOT_FOUND","Event was not found");
        var row=rows.getFirst();
        // Replay only a final state. Never steal an active or queued worker's claim.
        if(row.get("published_at")==null&&row.get("dead_lettered_at")==null)
            throw new ApiException(HttpStatus.CONFLICT,"EVENT_NOT_FINAL","Only published or dead-lettered events can be replayed");
        jdbc.update("""
            INSERT INTO outbox_replays(id,event_id,actor_user_id,team_id,reason,previous_attempts,previous_error,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),id,auth.getName(),team,request.reason(),row.get("attempts"),row.get("last_error"),Timestamp.from(clock.instant()));
        jdbc.update("""
            UPDATE outbox_events SET published_at=NULL,dead_lettered_at=NULL,attempts=0,last_error=NULL,
            lease_owner=NULL,lease_until=NULL,available_at=? WHERE id=? AND team_id=?
            """,Timestamp.from(clock.instant()),id,team);
        org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("eventId",id).addKeyValue("actorId",auth.getName()).log("Outbox replay queued");
        return Map.of("eventId",id,"result","REPLAY_QUEUED");
    }
    private void authorize(Authentication auth,String team){teams.requireRole(auth.getName(),team,Role.ADMIN,Role.INCIDENT_MANAGER);}
}
