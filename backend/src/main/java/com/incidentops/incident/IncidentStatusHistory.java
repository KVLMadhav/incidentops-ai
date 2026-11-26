package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="incident_status_history")
public class IncidentStatusHistory {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="incident_id",nullable=false,length=36) private String incidentId;
 @Column(name="actor_user_id",nullable=false,length=36) private String actorUserId;
 @Column(name="old_status",length=20) @Enumerated(EnumType.STRING) private IncidentStatus oldStatus;
 @Column(name="new_status",nullable=false,length=20) @Enumerated(EnumType.STRING) private IncidentStatus newStatus;
 @Column(name="reason",length=1000) private String reason;
 @Column(name="changed_at",nullable=false,columnDefinition="timestamp with time zone") private Instant changedAt;
 protected IncidentStatusHistory() {}
 public IncidentStatusHistory(String teamId, String incidentId, String actorUserId, IncidentStatus oldStatus, IncidentStatus newStatus, String reason,Instant now) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.incidentId=incidentId; this.actorUserId=actorUserId; this.oldStatus=oldStatus; this.newStatus=newStatus; this.reason=reason; this.changedAt=now;
 }
 public String getTeamId(){return teamId;}
 public String getIncidentId(){return incidentId;}
 public String getActorUserId(){return actorUserId;}
 public IncidentStatus getOldStatus(){return oldStatus;}
 public IncidentStatus getNewStatus(){return newStatus;}
 public String getReason(){return reason;}
 public Instant getChangedAt(){return changedAt;}
 public String getId(){return id;}
}
