package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="incident_assignments")
public class IncidentAssignment {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="incident_id",nullable=false,length=36) private String incidentId;
 @Column(name="actor_user_id",nullable=false,length=36) private String actorUserId;
 @Column(name="assignee_user_id",nullable=false,length=36) private String assigneeUserId;
 @Column(name="previous_assignee_user_id",length=36) private String previousAssigneeUserId;
 @Column(name="reason",length=1000) private String reason;
 @Column(name="assigned_at",nullable=false,columnDefinition="timestamp with time zone") private Instant assignedAt;
 protected IncidentAssignment() {}
 public IncidentAssignment(String teamId, String incidentId, String actorUserId, String assigneeUserId, String previousAssigneeUserId, String reason) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.incidentId=incidentId; this.actorUserId=actorUserId; this.assigneeUserId=assigneeUserId; this.previousAssigneeUserId=previousAssigneeUserId; this.reason=reason; this.assignedAt=Instant.now();
 }
 public String getTeamId(){return teamId;}
 public String getIncidentId(){return incidentId;}
 public String getActorUserId(){return actorUserId;}
 public String getAssigneeUserId(){return assigneeUserId;}
 public String getPreviousAssigneeUserId(){return previousAssigneeUserId;}
 public String getReason(){return reason;}
 public Instant getAssignedAt(){return assignedAt;}
 public String getId(){return id;}
}
