package com.incidentops.incident;

import com.incidentops.exception.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;

@Service
public class SLAPolicyService {
    public record Change(@NotNull @Min(1) Long expectedVersion,
                         @NotBlank @Size(max=100) String name,
                         @Min(1) @Max(31536000) long acknowledgementSeconds,
                         @Min(1) @Max(31536000) long resolutionSeconds, boolean active) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public SLAPolicyService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    private void lock(String team){
        jdbc.queryForList("SELECT lock_slot FROM sla_policy_locks WHERE lock_slot=? FOR UPDATE",Math.floorMod(team.hashCode(),256));
    }
    // Lazy defaults also support pre-Day-4 teams and fixtures; serialized across instances.
    @Transactional
    public List<SLAPolicy> current(String team){
        lock(team);
        for(Severity severity:Severity.values()) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sla_policies WHERE team_id=? AND severity=? AND legacy=FALSE",Long.class,team,severity.name())==0){
                long ack=switch(severity){case SEV1->300;case SEV2->900;case SEV3->3600;case SEV4->14400;};
                long resolve=switch(severity){case SEV1->3600;case SEV2->14400;case SEV3->43200;case SEV4->172800;};
                long version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM sla_policies WHERE team_id=? AND severity=?",Long.class,team,severity.name());
                insert(team,severity,"Default "+severity,ack,resolve,true,version);
            }
        }
        return jdbc.query("""
            SELECT p.* FROM sla_policies p WHERE team_id=? AND version=(
              SELECT MAX(q.version) FROM sla_policies q WHERE q.team_id=p.team_id AND q.severity=p.severity)
            ORDER BY severity
            """,(rs,n)->new SLAPolicy(rs.getString("id"),team,rs.getString("name"),Severity.valueOf(rs.getString("severity")),
                rs.getLong("acknowledgement_seconds"),rs.getLong("resolution_seconds"),rs.getBoolean("active"),
                rs.getLong("version"),rs.getTimestamp("effective_at").toInstant()),team);
    }
    @Transactional
    public SLAPolicy select(String team,Severity severity){
        var policy=current(team).stream().filter(p->p.severity()==severity).findFirst().orElseThrow();
        if(!policy.active())throw new ApiException(HttpStatus.CONFLICT,"SLA_POLICY_INACTIVE","Activate this severity's SLA policy before creating incidents");
        return policy;
    }
    @Transactional
    public SLAPolicy change(String team,Severity severity,Change change){
        var previous=current(team).stream().filter(p->p.severity()==severity).findFirst().orElseThrow();
        if(previous.version()!=change.expectedVersion())throw new ApiException(HttpStatus.CONFLICT,"STALE_VERSION","The policy changed; reload and review before saving");
        if(change.resolutionSeconds()<change.acknowledgementSeconds())
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_SLA_DURATION","Resolution seconds must be at least acknowledgement seconds");
        // Historical rows and existing instances never change. active describes that version's setting.
        insert(team,severity,change.name().trim(),change.acknowledgementSeconds(),change.resolutionSeconds(),change.active(),previous.version()+1);
        return current(team).stream().filter(p->p.severity()==severity).findFirst().orElseThrow();
    }
    private void insert(String team,Severity severity,String name,long ack,long resolve,boolean active,long version){
        jdbc.update("INSERT INTO sla_policies(id,team_id,name,severity,acknowledgement_seconds,resolution_seconds,active,version,effective_at) VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),team,name,severity.name(),ack,resolve,active,version,Timestamp.from(clock.instant()));
    }
}
