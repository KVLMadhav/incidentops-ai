package com.incidentops.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name="app.events.enabled",havingValue="true")
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;
    private final Clock clock;
    private final String topic;
    private final boolean scheduling;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public static final int MAX_ATTEMPTS=5;
    public OutboxPublisher(JdbcTemplate jdbc,TransactionTemplate tx,KafkaTemplate<String,String> kafka,
                           ObjectMapper json,Clock clock,@Value("${app.events.topic}") String topic,
                           @Value("${app.events.publisher-enabled:true}") boolean scheduling,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc=jdbc;this.tx=tx;this.kafka=kafka;this.json=json;this.clock=clock;this.topic=topic;this.scheduling=scheduling;this.metrics=metrics;
    }
    @Scheduled(fixedDelayString="${app.events.publish-delay-ms:1000}")
    public void scheduledPublish(){if(scheduling) publishBatch();}

    public int publishBatch() {
        requireOutsideTransaction();recoverExhausted();
        // Short leases are acquired one event at a time. No database transaction spans a broker send.
        var ids=jdbc.queryForList("""
            SELECT id FROM outbox_events WHERE published_at IS NULL AND dead_lettered_at IS NULL AND attempts<5 AND available_at<=?
              AND (lease_until IS NULL OR lease_until<=?) ORDER BY created_at,id LIMIT 20
            """,String.class,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        int sent=0;
        for(String id:ids) if(publishOne(id))sent++;
        return sent;
    }
    public boolean publishOne(String id) {
        requireOutsideTransaction();recoverExhausted();
        String owner=UUID.randomUUID().toString();
        Timestamp now=Timestamp.from(clock.instant());
        Boolean claimed=tx.execute(status->jdbc.update("""
            UPDATE outbox_events SET lease_owner=?,lease_until=?,attempts=attempts+1
            WHERE id=? AND published_at IS NULL AND dead_lettered_at IS NULL AND attempts<5 AND available_at<=?
              AND (lease_until IS NULL OR lease_until<=?)
            """,owner,Timestamp.from(clock.instant().plusSeconds(60)),id,now,now)==1);
        if(!Boolean.TRUE.equals(claimed))return false;
        try {
            var event=jdbc.queryForObject("SELECT o.*,i.service_id FROM outbox_events o JOIN incidents i ON i.id=o.aggregate_id WHERE o.id=?",(rs,n)->
                new IncidentEvent(id,1,rs.getString("event_type"),rs.getString("team_id"),rs.getString("aggregate_id"),rs.getString("service_id"),
                    rs.getTimestamp("created_at").toInstant(),clock.instant(),Objects.requireNonNullElse(rs.getString("correlation_id"),id)),id);
            kafka.send(topic,event.incidentId(),json.writeValueAsString(event)).get(8,TimeUnit.SECONDS);
            log.atInfo().addKeyValue("eventId",id).addKeyValue("correlationId",event.correlationId()).log("Outbox broker acknowledgement received");
            // A crash here may resend. Consumer receipts make that safe.
            return jdbc.update("""
                UPDATE outbox_events SET published_at=?,lease_owner=NULL,lease_until=NULL,last_error=NULL
                WHERE id=? AND lease_owner=? AND published_at IS NULL
                """,Timestamp.from(clock.instant()),id,owner)==1;
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            Integer attempt=jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,id);
            boolean dead=attempt!=null&&attempt>=MAX_ATTEMPTS;
            long delay=Math.min(60,1L<<Math.min(6,attempt==null?1:attempt));
            int updated=jdbc.update("""
                UPDATE outbox_events SET lease_owner=NULL,lease_until=NULL,available_at=?,last_error=?,dead_lettered_at=?
                WHERE id=? AND lease_owner=? AND published_at IS NULL
                """,Timestamp.from(clock.instant().plusSeconds(delay)),failureCategory(e),dead?Timestamp.from(clock.instant()):null,id,owner);
            if(updated==1) {
              metrics.counter("incidentops.outbox.publish.failures").increment();
              log.atWarn().addKeyValue("eventId",id).addKeyValue("correlationId",jdbc.queryForObject("SELECT correlation_id FROM outbox_events WHERE id=?",String.class,id)).addKeyValue("attempt",attempt)
                .addKeyValue("errorType",failureCategory(e)).addKeyValue("deadLetter",dead).log("Outbox publish failed");
            }
            return false;
        }
    }
    private static String failureCategory(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getClass().getSimpleName();}
    private static void requireOutsideTransaction(){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Publisher must run outside the business transaction");
    }
    private void recoverExhausted(){
        int exhausted=jdbc.update("""
            UPDATE outbox_events SET dead_lettered_at=?,lease_owner=NULL,lease_until=NULL,
              last_error=COALESCE(last_error,'Claim expired after final attempt')
            WHERE published_at IS NULL AND dead_lettered_at IS NULL AND attempts>=5
              AND (lease_until IS NULL OR lease_until<=?)
            """,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        if(exhausted>0)log.atWarn().addKeyValue("count",exhausted).log("Expired final outbox claims dead-lettered");
    }
}
