package com.incidentops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;

/** PostgreSQL is authoritative. Redis failures affect latency/freshness, never availability of reads. */
@Component
public class ReliabilityCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    public ReliabilityCache(StringRedisTemplate redis,ObjectMapper json,MeterRegistry metrics,
                            @Value("${app.cache.enabled:true}") boolean enabled) {
        this.redis=redis;this.json=json;this.metrics=metrics;this.enabled=enabled;
    }
    public static String detailKey(String team,String incident){return "incidentops:v1:team:"+team+":incident:"+incident;}
    public <T> T get(String key,Class<T> type) {
        if(!enabled)return null;
        try {
            String value=redis.opsForValue().get(key);
            T result=value==null?null:json.readValue(value,type);
            String outcome=result==null?"miss":"hit";
            metrics.counter("incidentops.cache.reads","result",outcome).increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).atDebug().addKeyValue("result",outcome).log("Cache read");
            return result;
        }catch(Exception e){failure(e);return null;}
    }
    public void putAfterCommit(String key,Object value) {
        if(!enabled)return;
        afterCommit(()->{try {redis.opsForValue().set(key,json.writeValueAsString(value),Duration.ofSeconds(10));}
            catch(Exception e){failure(e);}});
    }
    public void invalidateAfterCommit(String team,String incident) {
        if(!enabled)return;
        afterCommit(()->{
            List<String> keys=new ArrayList<>();keys.add(detailKey(team,incident));
            keys.add("incidentops:v1:team:"+team+":summary");
            // The API permits precisely 1..90 days; bounded deletion avoids wildcard scans.
            for(int days=1;days<=90;days++)keys.add("incidentops:v1:team:"+team+":dashboard:"+days);
            try{redis.delete(keys);}catch(Exception e){failure(e);}
        });
    }
    private void failure(Exception e) {
        metrics.counter("incidentops.redis.errors").increment();
        org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("errorType",e.getClass().getSimpleName()).log("Redis fallback to PostgreSQL");
    }
    private static void afterCommit(Runnable action) {
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
        else action.run();
    }
}
