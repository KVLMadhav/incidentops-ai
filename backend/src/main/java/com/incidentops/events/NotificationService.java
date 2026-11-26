package com.incidentops.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.incidentops.incident.OperationalEvidence evidence;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public NotificationService(JdbcTemplate jdbc,Clock clock,
                               io.micrometer.core.instrument.MeterRegistry metrics,com.incidentops.incident.OperationalEvidence evidence){
        this.jdbc=jdbc;this.clock=clock;
        this.metrics=metrics;
        this.evidence=evidence;
    }

    @Transactional
    public void process(IncidentEvent event) {
        validate(event);
        // Serialize effects for this aggregate, including duplicate deliveries from different partitions.
        var rows=jdbc.queryForList("SELECT team_id,service_id FROM incidents WHERE id=? FOR UPDATE",event.incidentId());
        if(rows.size()!=1||!event.teamId().equals(rows.getFirst().get("team_id")))
            throw new IllegalArgumentException("Event incident/team is invalid");
        if(!event.serviceId().equals(rows.getFirst().get("service_id")))throw new IllegalArgumentException("Event service is invalid");
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE consumer_name='notifications-v1' AND event_id=?",Integer.class,event.eventId());
        if(exists!=null&&exists>0){
            metrics.counter("incidentops.consumer.duplicates").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("correlationId",event.correlationId()).addKeyValue("eventId",event.eventId()).log("Duplicate event ignored");return;
        }
        Timestamp now=Timestamp.from(clock.instant());
        // Marker first, effect second; both roll back if any later step fails.
        jdbc.update("INSERT INTO processed_events(consumer_name,event_id,processed_at) VALUES ('notifications-v1',?,?)",event.eventId(),now);
        var contexts=jdbc.queryForList("SELECT notification_target,notification_message FROM outbox_events WHERE id=?",event.eventId());
        String target="TEAM",message=event.type().replace('_',' ');
        if(!contexts.isEmpty()){
            target=Objects.requireNonNullElse((String)contexts.getFirst().get("notification_target"),target);
            message=Objects.requireNonNullElse((String)contexts.getFirst().get("notification_message"),message);
        }
        String notification=UUID.randomUUID().toString(),delivery=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO incident_notifications(id,team_id,incident_id,event_id,event_type,message,created_at,target,correlation_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,notification,event.teamId(),event.incidentId(),event.eventId(),event.type(),message,now,target,event.correlationId());
        jdbc.update("""
            INSERT INTO notification_deliveries(id,notification_id,channel,target,status,created_at,available_at)
            VALUES (?,?,'IN_APP',?,'PENDING',?,?)
            """,delivery,notification,target,now,now);
        evidence.record(event.incidentId(),"NOTIFICATION_QUEUED","notification="+notification+" delivery="+delivery+" event="+event.eventId(),event.correlationId());
        org.slf4j.LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("correlationId",event.correlationId())
            .addKeyValue("eventId",event.eventId()).addKeyValue("notificationId",notification).addKeyValue("deliveryId",delivery).log("Notification queued for local in-app delivery");
    }
    private static void validate(IncidentEvent e) {
        if(e==null||e.schemaVersion()!=1||e.eventId()==null||e.teamId()==null||e.incidentId()==null||e.serviceId()==null
            ||e.occurredAt()==null||e.producedAt()==null||e.correlationId()==null||!e.correlationId().matches("[A-Za-z0-9._-]{1,64}")
            ||e.type()==null||!Set.of("INCIDENT_CREATED","INCIDENT_UPDATED","INCIDENT_ASSIGNED","INCIDENT_STATUS_CHANGED",
                "INCIDENT_ALERT_LINKED","INCIDENT_NOTE_ADDED","SLA_BREACHED").contains(e.type()))throw new IllegalArgumentException("Invalid event envelope");
        UUID.fromString(e.eventId()); UUID.fromString(e.teamId()); UUID.fromString(e.incidentId());
        UUID.fromString(e.serviceId());
    }
}
