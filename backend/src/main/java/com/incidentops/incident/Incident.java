package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="incidents")
public class Incident {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="service_id",nullable=false,length=36) private String serviceId;
 @Column(name="incident_number",nullable=false,unique=true,length=24) private String incidentNumber;
 @Column(nullable=false,length=200) private String title;
 @Column(length=4000) private String description;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=4) private Severity severity;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private IncidentStatus status;
 @Version @Column(nullable=false) private long version;
 @Column(name="creator_user_id",nullable=false,length=36) private String creatorUserId;
 @Column(name="assignee_user_id",length=36) private String assigneeUserId;
 @Column(name="created_at",nullable=false,columnDefinition="timestamp with time zone") private Instant createdAt;
 @Column(name="updated_at",nullable=false,columnDefinition="timestamp with time zone") private Instant updatedAt;
 protected Incident() {}
 public Incident(String teamId,String serviceId,String number,String title,String description,Severity severity,String actor) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.serviceId=serviceId; incidentNumber=number; this.title=title.trim(); this.description=description; this.severity=severity; status=IncidentStatus.OPEN; creatorUserId=actor; createdAt=Instant.now(); updatedAt=createdAt;
 }
 public void details(String title,String description,Severity severity){this.title=title.trim();this.description=description;this.severity=severity;updatedAt=Instant.now();}
 void status(IncidentStatus next){status=next;updatedAt=Instant.now();}
 void assignee(String userId){assigneeUserId=userId;updatedAt=Instant.now();}
 public String getId(){return id;} public String getTeamId(){return teamId;} public String getServiceId(){return serviceId;}
 public String getIncidentNumber(){return incidentNumber;} public String getTitle(){return title;} public String getDescription(){return description;}
 public Severity getSeverity(){return severity;} public IncidentStatus getStatus(){return status;} public long getVersion(){return version;}
 public String getCreatorUserId(){return creatorUserId;} public String getAssigneeUserId(){return assigneeUserId;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
