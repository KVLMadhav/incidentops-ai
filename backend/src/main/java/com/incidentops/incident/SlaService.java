package com.incidentops.incident;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.*;

@Service
public class SlaService {
    public record Target(String target,String policyVersion,Instant dueAt,Instant completedAt,Instant breachedAt,boolean breached) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean scanning;
    private final OutboxEventRepository outbox;
    private final ReliabilityCache cache;
    private final SLAPolicyService policies;
    private final OperationalEvidence evidence;
    private final TransactionTemplate tx, failureTx;
    private final AtomicLong lastScan=new AtomicLong(),failedScans=new AtomicLong();
    private static final Logger log=LoggerFactory.getLogger(SlaService.class);
    public long lastSuccessfulScan(){return lastScan.get();}
    public long failedScans(){return failedScans.get();}
    public boolean scanningEnabled(){return scanning;}
    public SlaService(JdbcTemplate jdbc,Clock clock,OutboxEventRepository outbox,
                      @Value("${app.sla.scanning-enabled:true}") boolean scanning,ReliabilityCache cache,
                      SLAPolicyService policies,OperationalEvidence evidence,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.clock=clock;this.outbox=outbox;this.scanning=scanning;this.cache=cache;
        this.policies=policies;this.evidence=evidence;this.tx=new TransactionTemplate(manager);
        this.tx.setTimeout(15);
        this.failureTx=new TransactionTemplate(manager);
        failureTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void create(Incident incident){
        var policy=policies.select(incident.getTeamId(),incident.getSeverity());
        String instance=UUID.randomUUID().toString(),correlation=correlation(instance),version="policy-"+policy.version();
        jdbc.update("INSERT INTO sla_instances(id,incident_id,policy_id,policy_version,severity,started_at,correlation_id) VALUES (?,?,?,?,?,?,?)",
            instance,incident.getId(),policy.id(),version,incident.getSeverity().name(),Timestamp.from(incident.getCreatedAt()),correlation);
        long mitigation=switch(incident.getSeverity()){case SEV1->1800;case SEV2->7200;case SEV3->28800;case SEV4->86400;};
        String[] names={"ACKNOWLEDGE","MITIGATE","RESOLVE"};
        long[] seconds={policy.acknowledgementSeconds(),mitigation,policy.resolutionSeconds()};
        for(int n=0;n<names.length;n++)jdbc.update("INSERT INTO incident_sla_targets(incident_id,target,policy_version,due_at) VALUES (?,?,?,?)",
            incident.getId(),names[n],version,Timestamp.from(incident.getCreatedAt().plusSeconds(seconds[n])));
    }
    public void complete(String id,IncidentStatus status){
        String target=switch(status){case ACKNOWLEDGED->"ACKNOWLEDGE";case MITIGATED->"MITIGATE";case RESOLVED->"RESOLVE";default->null;};
        if(target!=null){
            var now=Timestamp.from(clock.instant());
            jdbc.update("""
                UPDATE incident_sla_targets SET completed_at=?,pending=CASE WHEN ?<=due_at OR breached_at IS NOT NULL THEN FALSE ELSE TRUE END
                WHERE incident_id=? AND target=? AND completed_at IS NULL
                """,now,now,id,target);
        }
    }
    public SLAInstance instance(String id){
        return jdbc.queryForObject("SELECT * FROM sla_instances WHERE incident_id=?",(rs,n)->new SLAInstance(
            rs.getString("id"),id,rs.getString("policy_id"),rs.getString("policy_version"),Severity.valueOf(rs.getString("severity")),
            rs.getTimestamp("started_at").toInstant(),rs.getString("correlation_id"),targets(id)),id);
    }
    public List<Target> targets(String id){
        Instant now=clock.instant();
        return jdbc.query("SELECT * FROM incident_sla_targets WHERE incident_id=? ORDER BY due_at,target",(rs,n)->{
            Instant due=rs.getTimestamp("due_at").toInstant();
            Timestamp completion=rs.getTimestamp("completed_at"),detection=rs.getTimestamp("breached_at");
            Instant completed=completion==null?null:completion.toInstant();
            return new Target(rs.getString("target"),rs.getString("policy_version"),due,completed,
                detection==null?null:detection.toInstant(),detection!=null||due.isBefore(completed==null?now:completed));
        },id);
    }
    @Scheduled(fixedDelayString="${app.sla.scan-delay-ms:10000}")
    public void scheduledScan(){if(scanning)scan();}
    public int scan(){
        String scanId=UUID.randomUUID().toString();
        try {
            int changed=Objects.requireNonNull(tx.execute(status->scanLocked()));
            lastScan.set(clock.instant().getEpochSecond());return changed;
        }catch(RuntimeException error){
            failedScans.incrementAndGet();
            try {
                failureTx.executeWithoutResult(status->jdbc.update("INSERT INTO sla_scheduler_failures(id,correlation_id,error,created_at) VALUES (?,?,?,?)",
                    UUID.randomUUID().toString(),scanId,error.getClass().getSimpleName(),Timestamp.from(clock.instant())));
            }catch(RuntimeException unavailable){error.addSuppressed(unavailable);}
            log.atError().addKeyValue("correlationId",scanId).addKeyValue("errorType",error.getClass().getSimpleName()).log("SLA scan failed; transaction rolled back for retry");
            throw error;
        }
    }
    private int scanLocked(){
        var now=Timestamp.from(clock.instant());
        var rows=jdbc.queryForList("""
            SELECT s.incident_id,s.target,x.id AS instance_id,x.correlation_id
            FROM incident_sla_targets s JOIN sla_instances x ON x.incident_id=s.incident_id
            WHERE s.pending=TRUE AND s.due_at<? ORDER BY s.due_at,s.incident_id,s.target LIMIT 100
            """,now);
        int changed=0;
        // Same incident-before-target order as commands; sorted IDs prevent batch lock inversion.
        var ids=rows.stream().map(r->(String)r.get("incident_id")).distinct().sorted().toList();
        for(String id:ids)jdbc.queryForList("SELECT id FROM incidents WHERE id=? FOR UPDATE",id);
        for(var row:rows){
            String id=(String)row.get("incident_id"),target=(String)row.get("target"),correlation=(String)row.get("correlation_id");
            int won=jdbc.update("""
                UPDATE incident_sla_targets SET breached_at=?,pending=FALSE WHERE incident_id=? AND target=? AND pending=TRUE
                 AND breached_at IS NULL AND due_at<? AND (completed_at IS NULL OR completed_at>due_at)
                """,now,id,target,now);
            if(won==0)continue;
            String team=jdbc.queryForObject("SELECT team_id FROM incidents WHERE id=?",String.class,id);
            String breach=UUID.randomUUID().toString();
            String audience="RESOLVE".equals(target)?"TEAM_ADMINS_AND_MANAGERS":"TEAM_RESPONDERS_AND_MANAGERS";
            var event=outbox.saveAndFlush(new OutboxEvent(team,id,"SLA_BREACHED","{}",clock.instant(),correlation));
            jdbc.update("UPDATE outbox_events SET notification_target=?,notification_message=? WHERE id=?",audience,target+" SLA breached; escalate to "+audience,event.getId());
            jdbc.update("""
                INSERT INTO sla_breaches(id,instance_id,incident_id,target,due_at,detected_at,event_id,correlation_id)
                SELECT ?,?,?,target,due_at,?,?,? FROM incident_sla_targets WHERE incident_id=? AND target=?
                """,breach,row.get("instance_id"),id,now,event.getId(),correlation,id,target);
            evidence.record(id,"SLA_BREACHED","breach="+breach+" target="+target+" event="+event.getId(),correlation);
            cache.invalidateAfterCommit(team,id);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCommit(){
                        log.atWarn().addKeyValue("correlationId",correlation).addKeyValue("incidentId",id).addKeyValue("breachId",breach)
                            .addKeyValue("eventId",event.getId()).addKeyValue("target",target).log("SLA breach recorded");
                    }
                });
            changed++;
        }
        return changed;
    }
    public static String correlation(String fallback){return Objects.requireNonNullElse(MDC.get("correlationId"),fallback);}
}
