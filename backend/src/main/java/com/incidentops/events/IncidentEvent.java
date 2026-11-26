package com.incidentops.events;
import java.time.Instant;
/** Version 1 carries identifiers only, never sensitive notes or unrestricted metadata. */
public record IncidentEvent(String eventId,int schemaVersion,String type,String teamId,String incidentId,
                            String serviceId,Instant occurredAt,Instant producedAt,String correlationId) {}
