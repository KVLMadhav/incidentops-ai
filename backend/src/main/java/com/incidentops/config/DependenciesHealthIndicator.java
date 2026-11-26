package com.incidentops.config;

import org.springframework.boot.actuate.health.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.admin.AdminClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Redis is a degraded cache, Kafka is required only when asynchronous events are enabled. */
@Component("dependenciesHealthIndicator")
public class DependenciesHealthIndicator implements HealthIndicator {
    private final StringRedisTemplate redis;
    private final boolean eventsEnabled,cacheEnabled;
    private final String brokers;
    public DependenciesHealthIndicator(StringRedisTemplate redis,@Value("${app.events.enabled}") boolean eventsEnabled,
            @Value("${app.cache.enabled}") boolean cacheEnabled,@Value("${spring.kafka.bootstrap-servers}") String brokers){
        this.redis=redis;this.eventsEnabled=eventsEnabled;this.cacheEnabled=cacheEnabled;this.brokers=brokers;
    }
    @Override public Health health(){
        var health=Health.up();
        if(cacheEnabled){
            try(var connection=redis.getConnectionFactory().getConnection()){
                health.withDetail("redis","PONG".equals(connection.ping())?"UP":"DEGRADED");
            }catch(RuntimeException failure){health.withDetail("redis","DEGRADED_SQL_FALLBACK");}
        }else health.withDetail("redis","DISABLED");
        if(eventsEnabled){
            try(var admin=AdminClient.create(Map.of("bootstrap.servers",brokers,"request.timeout.ms",1000,"default.api.timeout.ms",1500))){
                admin.describeCluster().clusterId().get(1500,TimeUnit.MILLISECONDS);health.withDetail("kafka","UP");
            }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();health.down().withDetail("kafka","DOWN");}
        }else health.withDetail("kafka","DISABLED");
        return health.build();
    }
}
