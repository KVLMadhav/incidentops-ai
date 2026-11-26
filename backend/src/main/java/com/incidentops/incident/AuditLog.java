package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="audit_logs")
public class AuditLog {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="incident_id",nullable=false,length=36) private String incidentId;
 @Column(name="actor_user_id",nullable=false,length=36) private String actorUserId;
 @Column(name="action",nullable=false,length=40) private String action;
 @Column(name="detail",length=1000) private String detail;
 @Column(name="correlation_id",length=100) private String correlationId;
 @Column(name="created_at",nullable=false,columnDefinition="timestamp with time zone") private Instant createdAt;
 protected AuditLog() {}
 public AuditLog(String teamId, String incidentId, String actorUserId, String action, String detail,Instant now) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.incidentId=incidentId; this.actorUserId=actorUserId; this.action=action; this.detail=detail; this.createdAt=now;
  correlationId=SlaService.correlation(id);
 }
 public String getTeamId(){return teamId;}
 public String getIncidentId(){return incidentId;}
 public String getActorUserId(){return actorUserId;}
 public String getAction(){return action;}
 public String getDetail(){return detail;}
 public Instant getCreatedAt(){return createdAt;}
 public String getId(){return id;}
 public String getCorrelationId(){return correlationId;}
}
