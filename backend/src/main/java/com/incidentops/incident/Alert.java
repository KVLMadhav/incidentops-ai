package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="alerts")
public class Alert {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="service_id",nullable=false,length=36) private String serviceId;
 @Column(nullable=false,length=100) private String source;
 @Column(name="external_alert_id",nullable=false,length=150) private String externalAlertId;
 @Column(nullable=false,length=200) private String title;
 @Column(length=4000) private String description;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=4) private Severity severity;
 @Column(name="occurred_at",nullable=false,columnDefinition="timestamp with time zone") private Instant occurredAt;
 @Column(name="received_at",nullable=false,columnDefinition="timestamp with time zone") private Instant receivedAt;
 @Column(nullable=false,length=4000) private String metadata;
 protected Alert() {}
 public Alert(String teamId,String serviceId,String source,String externalAlertId,String title,String description,Severity severity,Instant occurredAt,String metadata) {
  this.id=UUID.randomUUID().toString(); this.teamId=teamId; this.serviceId=serviceId; this.source=source.trim(); this.externalAlertId=externalAlertId.trim(); this.title=title.trim(); this.description=description; this.severity=severity; this.occurredAt=occurredAt; this.receivedAt=Instant.now(); this.metadata=metadata;
 }
 public String getId(){return id;} public String getTeamId(){return teamId;} public String getServiceId(){return serviceId;}
 public String getSource(){return source;} public String getExternalAlertId(){return externalAlertId;} public String getTitle(){return title;}
 public String getDescription(){return description;} public Severity getSeverity(){return severity;} public Instant getOccurredAt(){return occurredAt;}
 public Instant getReceivedAt(){return receivedAt;} public String getMetadata(){return metadata;}
}
