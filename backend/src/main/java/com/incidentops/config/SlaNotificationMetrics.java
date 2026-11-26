package com.incidentops.config;

import com.incidentops.incident.Severity;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SlaNotificationMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;
    private final Map<String,AtomicLong> active=new HashMap<>(),upcoming=new HashMap<>(),breaches=new HashMap<>(),deliveries=new HashMap<>();
    private final AtomicLong sampleUp=new AtomicLong();
    public SlaNotificationMetrics(JdbcTemplate jdbc,Clock clock,MeterRegistry metrics,@Value("${app.ops.enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.clock=clock;this.enabled=enabled;
        for(Severity severity:Severity.values())register(metrics,active,severity.name(),"incidentops.incidents.active","severity");
        for(String target:List.of("ACKNOWLEDGE","MITIGATE","RESOLVE")){
            register(metrics,upcoming,target,"incidentops.sla.upcoming","target");
            register(metrics,breaches,target,"incidentops.sla.breaches","target");
        }
        for(String state:List.of("PENDING","PROCESSING","RETRY","DELIVERED","DEAD_LETTER"))register(metrics,deliveries,state,"incidentops.notifications.deliveries","status");
        Gauge.builder("incidentops.sla.notification.sample.up",sampleUp,AtomicLong::get).register(metrics);
    }
    private void register(MeterRegistry metrics,Map<String,AtomicLong> values,String value,String name,String label){
        var number=new AtomicLong();values.put(value,number);Gauge.builder(name,number,AtomicLong::get).tag(label,value).register(metrics);
    }
    @Scheduled(fixedDelayString="${app.ops.sample-delay-ms:5000}")
    public void scheduled(){if(enabled)sample();}
    public void sample(){
        try {
            active.forEach((severity,count)->count.set(jdbc.queryForObject("SELECT COUNT(*) FROM incidents WHERE severity=? AND status NOT IN ('RESOLVED','CLOSED')",Long.class,severity)));
            upcoming.forEach((target,count)->count.set(jdbc.queryForObject("SELECT COUNT(*) FROM incident_sla_targets WHERE pending=TRUE AND completed_at IS NULL AND target=? AND due_at>=? AND due_at<=?",Long.class,target,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(900)))));
            breaches.forEach((target,count)->count.set(jdbc.queryForObject("SELECT COUNT(*) FROM incident_sla_targets WHERE target=? AND breached_at IS NOT NULL",Long.class,target)));
            deliveries.forEach((state,count)->count.set(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE status=?",Long.class,state)));
            sampleUp.set(1);
        }catch(RuntimeException error){sampleUp.set(0);org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("errorType",error.getClass().getSimpleName()).log("SLA and notification metric sample failed");}
    }
}
