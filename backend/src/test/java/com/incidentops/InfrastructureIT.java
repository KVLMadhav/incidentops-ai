package com.incidentops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentops.catalog.*;
import com.incidentops.entity.*;
import com.incidentops.events.*;
import com.incidentops.identity.team.*;
import com.incidentops.incident.*;
import com.incidentops.repository.UserRepository;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles("postgres-it")
@DirtiesContext
@SpringBootTest(properties = {
    "app.events.enabled=true", "app.events.publisher-enabled=false", "app.cache.enabled=true"
})
class InfrastructureIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean java.time.Clock controlledTime;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean InAppDeliveryAdapter deliveryAdapter;
    @Autowired SlaService sla;
    @Autowired NotificationDeliveryWorker deliveryWorker;
    @Autowired com.incidentops.config.SlaNotificationMetrics slaMetrics;
    @Autowired org.springframework.boot.actuate.health.HealthEndpoint health;
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)
        // Keep the container endpoint stable while stopping/restarting the real Redis server.
        .withCommand("sh","-c","redis-server --daemonize yes --save '' --appendonly no; exec tail -f /dev/null");

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository members;
    @Autowired MonitoredServiceRepository services;
    @Autowired IncidentService incidents;
    @Autowired TeamReadService reads;
    @Autowired OutboxEventRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

    record Fixture(User admin, Team team, MonitoredService service) {}

    @Test void controlledBreachTravelsThroughPostgresOutboxRealKafkaAndBoundedLocalDelivery() throws Exception {
        var start=java.time.Instant.parse("2026-11-06T00:00:00Z");
        org.mockito.Mockito.doReturn(start).when(controlledTime).instant();
        Fixture f=fixture();
        com.incidentops.incident.IncidentDtos.IncidentResponse incident;
        try(var context=org.slf4j.MDC.putCloseable("correlationId","day4-real-kafka-proof")){
            incident=incidents.create(f.admin.getId(),f.team.getId(),new IncidentDtos.CreateIncidentRequest(f.service.getId(),"Day4 real broker breach",null,Severity.SEV1,List.of()));
        }
        org.mockito.Mockito.doReturn(start.plusSeconds(301)).when(controlledTime).instant();
        assertThat(sla.scan()).isGreaterThanOrEqualTo(1);assertThat(sla.scan()).isZero();
        String event=jdbc.queryForObject("SELECT event_id FROM sla_breaches WHERE incident_id=? AND target='ACKNOWLEDGE'",String.class,incident.id());
        assertThat(publisher.publishOne(event)).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?",Long.class,event)).isEqualTo(1));
        String notification=jdbc.queryForObject("SELECT id FROM incident_notifications WHERE event_id=?",String.class,event);
        String delivery=jdbc.queryForObject("SELECT id FROM notification_deliveries WHERE notification_id=?",String.class,notification);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected local delivery failure")).doNothing().when(deliveryAdapter).deliver(org.mockito.ArgumentMatchers.eq(delivery),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThat(deliveryWorker.deliver(delivery)).isFalse();assertThat(deliveryWorker.deliver(delivery)).isFalse();
        org.mockito.Mockito.doReturn(start.plusSeconds(303)).when(controlledTime).instant();
        assertThat(deliveryWorker.deliver(delivery)).isTrue();assertThat(deliveryWorker.deliver(delivery)).isFalse();
        assertThat(jdbc.queryForList("SELECT status FROM notification_delivery_attempts WHERE delivery_id=? ORDER BY attempt",String.class,delivery)).containsExactly("FAILED","DELIVERED");
        assertThat(incidents.timeline(f.admin.getId(),f.team.getId(),incident.id())).extracting(IncidentDtos.TimelineEntry::type).contains("SLA_BREACHED","NOTIFICATION_RETRY","NOTIFICATION_DELIVERED");
        assertThat(incidents.audit(f.admin.getId(),f.team.getId(),incident.id())).extracting(AuditLog::getCorrelationId).contains("day4-real-kafka-proof");
        slaMetrics.sample();assertThat(metrics.get("incidentops.sla.breaches").tag("target","ACKNOWLEDGE").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        System.out.println("DAY4_REAL_KAFKA correlation=day4-real-kafka-proof incident="+incident.id()+" event="+event+" notification="+notification+" delivery="+delivery+" attempts=FAILED,DELIVERED health=UP");
    }

    Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        var admin = users.save(new User(suffix + "@example.test", "Release verification", "unused", Role.ADMIN));
        var team = teams.save(new Team("Release " + suffix));
        members.save(new TeamMember(team.getId(), admin.getId(), Role.ADMIN));
        var service = services.save(new MonitoredService(team.getId(), "Checkout", null, null, suffix));
        return new Fixture(admin, team, service);
    }

    IncidentDtos.IncidentResponse create(Fixture f) {
        return incidents.create(f.admin().getId(), f.team().getId(),
            new IncidentDtos.CreateIncidentRequest(f.service().getId(), "Checkout unavailable", null, Severity.SEV2, List.of()));
    }

    @Test void committedOutboxReachesBrokerAndDuplicateHasOneEffect() throws Exception {
        var f = fixture();
        var incident = create(f);
        var event = outbox.findAll().stream().filter(e -> e.getAggregateId().equals(incident.id())).findFirst().orElseThrow();
        assertThat(publisher.publishOne(event.getId())).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?", Integer.class, event.getId())).isEqualTo(1));

        var props = new HashMap<String, Object>();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "release-proof-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", false);
        try (var dlt = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            dlt.subscribe(List.of("incidentops.incidents.v1.DLT"));
            var wire = new IncidentEvent(event.getId(),1,event.getEventType(),f.team().getId(),incident.id(),f.service().getId(),event.getCreatedAt(),java.time.Instant.now(),event.getId());
            kafka.send("incidentops.incidents.v1", incident.id(), json.writeValueAsString(wire)).get(10, TimeUnit.SECONDS);
            double failuresBefore=metrics.counter("incidentops.consumer.failures").count();
            kafka.send("incidentops.incidents.v1", incident.id(), "{invalid-json").get(10, TimeUnit.SECONDS);
            var dead=KafkaTestUtils.getSingleRecord(dlt, "incidentops.incidents.v1.DLT", Duration.ofSeconds(30));
            assertThat(dead.value()).isEqualTo("{invalid-json");assertThat(dead.key()).isEqualTo(incident.id());
            assertThat(dead.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
            assertThat(dead.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
            assertThat(metrics.counter("incidentops.consumer.failures").count()-failuresBefore).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?", Integer.class, event.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?", Integer.class, event.getId())).isEqualTo(1);
            assertThat(outbox.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
        }
    }

    @Test void realRedisCachesThenRefreshesAfterExpiryAndRevocationStillWins() {
        var f = fixture();
        String key = "incidentops:v1:team:" + f.team().getId() + ":summary";
        assertThat(reads.summary(f.admin().getId(), f.team().getId()).total()).isZero();
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 10000L);
        create(f);
        assertThat(reads.summary(f.admin().getId(), f.team().getId()).total()).isEqualTo(1);
        redis.expire(key, Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
            reads.summary(f.admin().getId(), f.team().getId()).total()).isEqualTo(1));
        jdbc.update("DELETE FROM team_members WHERE team_id=? AND user_id=?", f.team().getId(), f.admin().getId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reads.summary(f.admin().getId(), f.team().getId()))
            .isInstanceOf(com.incidentops.exception.ApiException.class);
    }

    @Test void temporaryPublishFailureAndExpiredClaimEventuallyReachRealKafka() throws Exception {
        var f=fixture();var incident=create(f);
        String id=outbox.findAll().stream().filter(e->e.getAggregateId().equals(incident.id())).findFirst().orElseThrow().getId();
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> transport=org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(transport.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("temporary disconnect")));
        var worker=new OutboxPublisher(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactionManager),transport,json,
            java.time.Clock.systemUTC(),"incidentops.incidents.v1",false,metrics);
        assertThat(worker.publishOne(id)).isFalse();
        assertThat(outbox.findById(id).orElseThrow().getPublishedAt()).isNull();
        jdbc.update("UPDATE outbox_events SET available_at=CURRENT_TIMESTAMP,lease_owner=?,lease_until=? WHERE id=?",
            UUID.randomUUID().toString(),java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)),id);
        assertThat(publisher.publishOne(id)).isFalse();
        jdbc.update("UPDATE outbox_events SET lease_until=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),id);
        assertThat(publisher.publishOne(id)).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?",Integer.class,id)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,id)).isEqualTo(2);
    }

    @Test void stoppedRedisPreservesIncidentDetailAndSummaryThenRecovers() throws Exception {
        var f=fixture();var incident=create(f);String actor=f.admin().getId(),team=f.team().getId();
        String key=ReliabilityCache.detailKey(team,incident.id());
        assertThat(incidents.read(actor,team,incident.id()).id()).isEqualTo(incident.id());
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,10000L);
        reads.summary(actor,team);
        incidents.details(actor,team,incident.id(),new IncidentDtos.DetailsRequest(incident.version(),"Committed update",null,Severity.SEV2));
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey("incidentops:v1:team:"+team+":summary")).isFalse();
        assertThat(REDIS.execInContainer("redis-cli","shutdown","nosave").getExitCode()).isZero();
        try {
            assertThat(incidents.read(actor,team,incident.id()).title()).isEqualTo("Committed update");
            assertThat(reads.summary(actor,team).total()).isEqualTo(1);
        }finally{assertThat(REDIS.execInContainer("redis-server","--daemonize","yes","--save","","--appendonly","no").getExitCode()).isZero();}
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->{
            assertThat(incidents.read(actor,team,incident.id()).title()).isEqualTo("Committed update");
            assertThat(redis.hasKey(key)).isTrue();
        });
        assertThat(metrics.counter("incidentops.redis.errors").count()).isGreaterThan(0);
    }
}
