package com.incidentops.incident;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class IncidentDtos {
 private IncidentDtos(){}
 public record CreateAlertRequest(@NotBlank @Size(max=36) String serviceId,@NotBlank @Size(max=100) String source,
  @NotBlank @Size(max=150) String externalAlertId,@NotBlank @Size(max=200) String title,
  @Size(max=4000) String description,@NotNull Severity severity,@NotNull Instant occurredAt,JsonNode metadata){}
 public record AlertResponse(String id,String teamId,String serviceId,String source,String externalAlertId,String title,
  String description,Severity severity,Instant occurredAt,Instant receivedAt,JsonNode metadata){}
 public record CreateIncidentRequest(@NotBlank @Size(max=36) String serviceId,@NotBlank @Size(max=200) String title,
  @Size(max=4000) String description,@NotNull Severity severity,@Size(max=50) List<@NotBlank @Size(max=36) String> alertIds){}
 public record IncidentResponse(String id,String teamId,String serviceId,String incidentNumber,String title,String description,
  Severity severity,IncidentStatus status,long version,String creatorUserId,String assigneeUserId,Instant createdAt,Instant updatedAt){}
 public record DetailsRequest(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=200) String title,@Size(max=4000) String description,@NotNull Severity severity){}
 public record AssignRequest(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=36) String assigneeUserId,@Size(max=1000) String reason,@Size(max=36) String commandId){
  public AssignRequest(Long version,String assigneeUserId,String reason){this(version,assigneeUserId,reason,null);}
 }
 public record TransitionRequest(@NotNull @PositiveOrZero Long version,@NotNull IncidentStatus status,@Size(max=1000) String reason,@Size(max=36) String commandId){
  public TransitionRequest(Long version,IncidentStatus status,String reason){this(version,status,reason,null);}
 }
 public record LinkAlertRequest(@NotBlank @Size(max=36) String alertId){}
 public record NoteRequest(@NotBlank @Size(max=4000) String body,@Size(max=36) String commandId){
  public NoteRequest(String body){this(body,null);}
 }
 public record PageResponse<T>(List<T> items,int page,int size,long totalElements,int totalPages){}
 public record TimelineEntry(String id,String type,Instant at,String actorUserId,String detail){}
 public record AuditEntry(String id, String teamId, String incidentId, String actorUserId,
                          String action, String detail, String correlationId, Instant createdAt) {
  static AuditEntry from(AuditLog entry) {
   return new AuditEntry(entry.getId(), entry.getTeamId(), entry.getIncidentId(), entry.getActorUserId(),
    entry.getAction(), entry.getDetail(), entry.getCorrelationId(), entry.getCreatedAt());
  }
 }
}
