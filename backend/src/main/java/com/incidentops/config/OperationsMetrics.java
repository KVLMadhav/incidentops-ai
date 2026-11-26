package com.incidentops.config;

import com.incidentops.incident.SlaService;
import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.time.Clock;
@Component
public class OperationsMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;
    private volatile double pending,oldestAge,attempts,sampleUp,lastSample,deadLetters;
    public OperationsMetrics(JdbcTemplate jdbc,Clock clock,SlaService sla,MeterRegistry registry,
                             @Value("${app.ops.enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.clock=clock;this.enabled=enabled;
        Gauge.builder("incidentops.outbox.pending",this,m->m.pending).register(registry);
        Gauge.builder("incidentops.outbox.dlq",this,m->m.deadLetters).register(registry);
        Gauge.builder("incidentops.outbox.oldest.age.seconds",this,m->m.oldestAge).register(registry);
        Gauge.builder("incidentops.outbox.pending.attempts",this,m->m.attempts).register(registry);
        Gauge.builder("incidentops.ops.sample.up",this,m->m.sampleUp).register(registry);
        Gauge.builder("incidentops.ops.last.sample.timestamp.seconds",this,m->m.lastSample).register(registry);
        Gauge.builder("incidentops.sla.last.success.timestamp.seconds",sla,SlaService::lastSuccessfulScan).register(registry);
        Gauge.builder("incidentops.sla.failed.scans",sla,SlaService::failedScans).register(registry);
        Gauge.builder("incidentops.sla.scanning.enabled",sla,s->s.scanningEnabled()?1:0).register(registry);
    }
    @Scheduled(fixedDelayString="${app.ops.sample-delay-ms:5000}")
    public void sample(){
        if(!enabled)return;
        try {
            deadLetters=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE dead_lettered_at IS NOT NULL",Long.class);
            jdbc.query("SELECT COUNT(*),MIN(created_at),COALESCE(SUM(attempts),0) FROM outbox_events WHERE published_at IS NULL AND dead_lettered_at IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    pending=rs.getLong(1);var oldest=rs.getTimestamp(2);
                    oldestAge=oldest==null?0:Math.max(0,Duration.between(oldest.toInstant(),clock.instant()).toSeconds());
                    attempts=rs.getLong(3);
                });
            sampleUp=1;lastSample=clock.instant().getEpochSecond();
            org.slf4j.LoggerFactory.getLogger(getClass()).atDebug().addKeyValue("pending",pending)
                .addKeyValue("oldestAgeSeconds",oldestAge).addKeyValue("deadLetters",deadLetters).log("Outbox backlog sampled");
        }catch(RuntimeException e){sampleUp=0;org.slf4j.LoggerFactory.getLogger(getClass()).warn("Operations metric sampling failed ({})",e.getClass().getSimpleName());}
    }
}
