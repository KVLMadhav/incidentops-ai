package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="incident_notes")
public class IncidentNote {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="incident_id",nullable=false,length=36) private String incidentId;
 @Column(name="actor_user_id",nullable=false,length=36) private String actorUserId;
 @Column(name="body",nullable=false,length=4000) private String body;
 @Column(name="created_at",nullable=false,columnDefinition="timestamp with time zone") private Instant createdAt;
 protected IncidentNote() {}
 public IncidentNote(String teamId, String incidentId, String actorUserId, String body,Instant now) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.incidentId=incidentId; this.actorUserId=actorUserId; this.body=body; this.createdAt=now;
 }
 public String getTeamId(){return teamId;}
 public String getIncidentId(){return incidentId;}
 public String getActorUserId(){return actorUserId;}
 public String getBody(){return body;}
 public Instant getCreatedAt(){return createdAt;}
 public String getId(){return id;}
}
