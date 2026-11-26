package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="incident_alerts")
public class IncidentAlert {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="incident_id",nullable=false,length=36) private String incidentId;
 @Column(name="actor_user_id",nullable=false,length=36) private String actorUserId;
 @Column(name="alert_id",nullable=false,length=36) private String alertId;
 @Column(name="linked_at",nullable=false,columnDefinition="timestamp with time zone") private Instant linkedAt;
 protected IncidentAlert() {}
 public IncidentAlert(String teamId, String incidentId, String actorUserId, String alertId,Instant now) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.incidentId=incidentId; this.actorUserId=actorUserId; this.alertId=alertId; this.linkedAt=now;
 }
 public String getTeamId(){return teamId;}
 public String getIncidentId(){return incidentId;}
 public String getActorUserId(){return actorUserId;}
 public String getAlertId(){return alertId;}
 public Instant getLinkedAt(){return linkedAt;}
 public String getId(){return id;}
}
