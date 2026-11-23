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
  @Size(max=4000) String description,@NotNull Severity severity,List<@NotBlank String> alertIds){}
 public record IncidentResponse(String id,String teamId,String serviceId,String incidentNumber,String title,String description,
  Severity severity,IncidentStatus status,long version,String creatorUserId,String assigneeUserId,Instant createdAt,Instant updatedAt){}
 public record DetailsRequest(@NotNull Long version,@NotBlank @Size(max=200) String title,@Size(max=4000) String description,@NotNull Severity severity){}
 public record AssignRequest(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=36) String assigneeUserId,@Size(max=1000) String reason){}
 public record TransitionRequest(@NotNull @PositiveOrZero Long version,@NotNull IncidentStatus status,@Size(max=1000) String reason){}
 public record LinkAlertRequest(@NotBlank String alertId){}
 public record NoteRequest(@NotBlank @Size(max=4000) String body){}
 public record AuditResponse(String id,String teamId,String incidentId,String actorUserId,String action,String detail,Instant createdAt){}
 public record PageResponse<T>(List<T> items,int page,int size,long totalElements,int totalPages){}
 public record TimelineEntry(String id,String type,Instant at,String actorUserId,String detail){}
}
