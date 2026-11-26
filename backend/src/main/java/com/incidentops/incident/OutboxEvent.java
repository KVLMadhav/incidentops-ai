package com.incidentops.incident;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="outbox_events")
public class OutboxEvent {
 @Id @Column(length=36) private String id;
 @Column(name="team_id",nullable=false,length=36) private String teamId;
 @Column(name="aggregate_id",nullable=false,length=36) private String aggregateId;
 @Column(name="event_type",nullable=false,length=80) private String eventType;
 @Column(name="payload",nullable=false,length=4000) private String payload;
 @Column(name="created_at",nullable=false,columnDefinition="timestamp with time zone") private Instant createdAt;
 @Column(name="published_at",columnDefinition="timestamp with time zone") private Instant publishedAt;
 @Column(name="available_at",nullable=false,updatable=false,columnDefinition="timestamp with time zone") private Instant availableAt;
 @Column(name="correlation_id",length=100) private String correlationId;
 protected OutboxEvent() {}
 public OutboxEvent(String teamId, String aggregateId, String eventType, String payload,Instant now,String correlation) {
  id=UUID.randomUUID().toString(); this.teamId=teamId; this.aggregateId=aggregateId; this.eventType=eventType; this.payload=payload; this.createdAt=now;this.availableAt=now;
  correlationId=correlation==null?org.slf4j.MDC.get("correlationId"):correlation;if(correlationId==null)correlationId=id;
 }
 public String getTeamId(){return teamId;}
 public String getAggregateId(){return aggregateId;}
 public String getEventType(){return eventType;}
 public String getPayload(){return payload;}
 public Instant getCreatedAt(){return createdAt;}
 public Instant getPublishedAt(){return publishedAt;}
 public String getId(){return id;}
}
