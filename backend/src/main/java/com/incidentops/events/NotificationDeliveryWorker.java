package com.incidentops.events;

import com.incidentops.incident.OperationalEvidence;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.slf4j.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Component
public class NotificationDeliveryWorker {
    public static final int MAX_ATTEMPTS=3;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final InAppDeliveryAdapter adapter;
    private final OperationalEvidence evidence;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private static final Logger log=LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    public NotificationDeliveryWorker(JdbcTemplate jdbc,Clock clock,PlatformTransactionManager manager,
            InAppDeliveryAdapter adapter,OperationalEvidence evidence,MeterRegistry metrics,
            @Value("${app.notifications.delivery-enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.clock=clock;this.tx=new TransactionTemplate(manager);this.adapter=adapter;
        this.evidence=evidence;this.metrics=metrics;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${app.notifications.delivery-delay-ms:1000}")
    public void scheduled(){if(enabled)runBatch();}
    public int runBatch(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Delivery must run outside a business transaction");
        var now=Timestamp.from(clock.instant());
        var ids=jdbc.queryForList("""
            SELECT id FROM notification_deliveries
            WHERE status IN ('PENDING','RETRY','PROCESSING') AND available_at<=? AND (lease_until IS NULL OR lease_until<=?)
            ORDER BY available_at,id LIMIT 50
            """,String.class,now,now);
        int completed=0;
        for(String id:ids)if(deliver(id))completed++;
        return completed;
    }
    public boolean deliver(String id){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Delivery must run outside a business transaction");
        String owner=UUID.randomUUID().toString();
        var row=tx.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM notification_deliveries WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty())return null;
            var work=rows.getFirst();String state=(String)work.get("status");
            var due=jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_deliveries WHERE id=? AND status IN ('PENDING','RETRY','PROCESSING')
                AND available_at<=? AND (lease_until IS NULL OR lease_until<=?)
                """,Integer.class,id,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
            if(due==null||due==0)return null;
            int attempt=((Number)work.get("attempts")).intValue();
            if("PROCESSING".equals(state)){
                jdbc.update("UPDATE notification_delivery_attempts SET status='FAILED',finished_at=?,error='LeaseExpired' WHERE delivery_id=? AND attempt=? AND finished_at IS NULL",
                    Timestamp.from(clock.instant()),id,attempt);
            }
            if(attempt>=MAX_ATTEMPTS){
                jdbc.update("UPDATE notification_deliveries SET status='DEAD_LETTER',completed_at=?,lease_owner=NULL,lease_until=NULL,last_error='LeaseExpired' WHERE id=?",Timestamp.from(clock.instant()),id);
                var context=context(id);evidence.record((String)context.get("incident_id"),"NOTIFICATION_DEAD_LETTER","delivery="+id+" attempts="+attempt,(String)context.get("correlation_id"));
                return null;
            }
            jdbc.update("UPDATE notification_deliveries SET status='PROCESSING',attempts=attempts+1,lease_owner=?,lease_until=? WHERE id=?",
                owner,Timestamp.from(clock.instant().plusSeconds(30)),id);
            jdbc.update("INSERT INTO notification_delivery_attempts(delivery_id,attempt,started_at,status) VALUES (?,?,?,'STARTED')",id,attempt+1,Timestamp.from(clock.instant()));
            return context(id);
        });
        if(row==null)return false;
        String error=null;
        try { adapter.deliver(id,(String)row.get("channel"),(String)row.get("target")); }
        catch(RuntimeException failure){error=failure.getClass().getSimpleName();if(error.length()>200)error=error.substring(0,200);}
        String category=error;
        return Boolean.TRUE.equals(tx.execute(status->{
            int attempts=((Number)row.get("attempts")).intValue();
            String next=category==null?"DELIVERED":attempts>=MAX_ATTEMPTS?"DEAD_LETTER":"RETRY";
            int changed=jdbc.update("""
                UPDATE notification_deliveries SET status=?,available_at=?,completed_at=?,last_error=?,lease_owner=NULL,lease_until=NULL
                WHERE id=? AND lease_owner=? AND status='PROCESSING'
                """,next,Timestamp.from(clock.instant().plusSeconds(1L<<attempts)),
                ("DELIVERED".equals(next)||"DEAD_LETTER".equals(next))?Timestamp.from(clock.instant()):null,category,id,owner);
            if(changed==0)return false;
            jdbc.update("UPDATE notification_delivery_attempts SET status=?,finished_at=?,error=? WHERE delivery_id=? AND attempt=? AND finished_at IS NULL",
                category==null?"DELIVERED":"FAILED",Timestamp.from(clock.instant()),category,id,attempts);
            String correlation=(String)row.get("correlation_id");
            evidence.record((String)row.get("incident_id"),"NOTIFICATION_"+next,"notification="+row.get("notification_id")+" delivery="+id+" attempt="+attempts,correlation);
            log.atInfo().addKeyValue("correlationId",correlation).addKeyValue("notificationId",row.get("notification_id"))
                .addKeyValue("deliveryId",id).addKeyValue("attempt",attempts).addKeyValue("status",next).addKeyValue("errorType",category).log("In-app notification delivery result");
            if(category!=null)metrics.counter("incidentops.notifications.delivery.failures").increment();
            if("RETRY".equals(next))metrics.counter("incidentops.notifications.retries").increment();
            return category==null;
        }));
    }
    private Map<String,Object> context(String id){
        return jdbc.queryForMap("""
            SELECT d.*,n.incident_id,COALESCE(n.correlation_id,n.event_id) AS correlation_id
            FROM notification_deliveries d JOIN incident_notifications n ON n.id=d.notification_id WHERE d.id=?
            """,id);
    }
}
