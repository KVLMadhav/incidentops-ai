package com.incidentops.incident;

import com.incidentops.entity.Role;
import com.incidentops.identity.team.TeamService;
import com.incidentops.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class SLAController {
    private final TeamService teams;
    private final SLAPolicyService policies;
    private final SlaService sla;
    private final IncidentService incidents;
    private final JdbcTemplate jdbc;
    public SLAController(TeamService teams,SLAPolicyService policies,SlaService sla,IncidentService incidents,JdbcTemplate jdbc){
        this.teams=teams;this.policies=policies;this.sla=sla;this.incidents=incidents;this.jdbc=jdbc;
    }
    @GetMapping("/sla/policies")
    public List<SLAPolicy> policies(Authentication auth,@PathVariable String teamId){
        teams.requireMembership(auth.getName(),teamId);return policies.current(teamId);
    }
    @PutMapping("/sla/policies/{severity}")
    public SLAPolicy change(Authentication auth,@PathVariable String teamId,@PathVariable Severity severity,@Valid @RequestBody SLAPolicyService.Change request){
        teams.requireRole(auth.getName(),teamId,Role.ADMIN,Role.INCIDENT_MANAGER);return policies.change(teamId,severity,request);
    }
    @GetMapping("/incidents/{id}/sla-instance")
    public SLAInstance instance(Authentication auth,@PathVariable String teamId,@PathVariable String id){
        incidents.sla(auth.getName(),teamId,id);return sla.instance(id);
    }
    @GetMapping("/sla/breaches")
    public List<Map<String,Object>> breaches(Authentication auth,@PathVariable String teamId){
        teams.requireMembership(auth.getName(),teamId);
        return jdbc.queryForList("SELECT b.* FROM sla_breaches b JOIN incidents i ON i.id=b.incident_id WHERE i.team_id=? ORDER BY b.detected_at DESC,b.id LIMIT 100",teamId);
    }
    @GetMapping("/sla/active")
    public List<Map<String,Object>> active(Authentication auth,@PathVariable String teamId){
        teams.requireMembership(auth.getName(),teamId);
        return jdbc.queryForList("""
            SELECT s.*,x.id AS instance_id,i.incident_number FROM incident_sla_targets s
            JOIN incidents i ON i.id=s.incident_id JOIN sla_instances x ON x.incident_id=i.id
            WHERE i.team_id=? AND s.completed_at IS NULL ORDER BY s.due_at,i.id,s.target LIMIT 100
            """,teamId);
    }
    @GetMapping("/notifications/{notificationId}/deliveries")
    public List<Map<String,Object>> deliveries(Authentication auth,@PathVariable String teamId,@PathVariable String notificationId){
        requireNotification(auth.getName(),teamId,notificationId);
        return jdbc.queryForList("SELECT * FROM notification_deliveries WHERE notification_id=? ORDER BY id",notificationId);
    }
    @GetMapping("/notifications/{notificationId}/attempts")
    public List<Map<String,Object>> attempts(Authentication auth,@PathVariable String teamId,@PathVariable String notificationId){
        requireNotification(auth.getName(),teamId,notificationId);
        return jdbc.queryForList("""
            SELECT a.* FROM notification_delivery_attempts a JOIN notification_deliveries d ON d.id=a.delivery_id
            WHERE d.notification_id=? ORDER BY a.delivery_id,a.attempt LIMIT 100
            """,notificationId);
    }
    private void requireNotification(String actor,String team,String id){
        teams.requireMembership(actor,team);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE id=? AND team_id=?",Long.class,id,team)==0)
            throw new ApiException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","Notification was not found");
    }
}
