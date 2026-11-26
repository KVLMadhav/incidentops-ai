package com.incidentops;

import com.incidentops.catalog.*;
import com.incidentops.entity.*;
import com.incidentops.identity.team.*;
import com.incidentops.incident.*;
import com.incidentops.events.*;
import com.incidentops.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:kafka_delivery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.events.enabled=true","app.events.publisher-enabled=false","spring.kafka.listener.auto-startup=true",
    "logging.level.org.apache.kafka=WARN","logging.level.kafka=WARN","logging.level.state.change.logger=WARN"
})
@ActiveProfiles("test")
@DirtiesContext
@EmbeddedKafka(partitions=3,topics={"incidentops.incidents.v1","incidentops.incidents.v1.DLT"},
    bootstrapServersProperty="spring.kafka.bootstrap-servers",kraft=true)
class KafkaDeliveryIT {
    @Autowired UserRepository users; @Autowired TeamRepository teams; @Autowired TeamMemberRepository members;
    @Autowired MonitoredServiceRepository services; @Autowired IncidentService incidents;
    @Autowired OutboxEventRepository outbox; @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String,String> kafka; @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;

    @Test void brokerPublicationDuplicateDeliveryAndPoisonMessageRecovery() throws Exception {
        var admin=users.save(new User("kafka-admin@example.test","Kafka test","unused",Role.ADMIN));
        var team=teams.save(new Team("Kafka test"));
        members.save(new TeamMember(team.getId(),admin.getId(),Role.ADMIN));
        var service=services.save(new MonitoredService(team.getId(),"Broker test",null,null,"broker-test"));
        var incident=incidents.create(admin.getId(),team.getId(),new IncidentDtos.CreateIncidentRequest(service.getId(),"Kafka delivery",null,Severity.SEV2,List.of()));
        var event=outbox.findAll().getFirst();
        var props=KafkaTestUtils.consumerProps("dlt-proof-"+UUID.randomUUID(),"false",broker);
        props.put("auto.offset.reset","earliest");
        try(var dlt=new DefaultKafkaConsumerFactory<>(props,new StringDeserializer(),new StringDeserializer()).createConsumer()) {
            dlt.subscribe(List.of("incidentops.incidents.v1.DLT"));
            assertThat(publisher.publishOne(event.getId())).isTrue();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?",Integer.class,event.getId())).isEqualTo(1));
            var wire=new IncidentEvent(event.getId(),1,event.getEventType(),team.getId(),incident.id(),service.getId(),event.getCreatedAt(),java.time.Instant.now(),event.getId());
            kafka.send("incidentops.incidents.v1",incident.id(),json.writeValueAsString(wire)).get(10,TimeUnit.SECONDS);
            // Same partition: DLT arrival proves that the preceding duplicate was handled.
            kafka.send("incidentops.incidents.v1",incident.id(),"{invalid-json").get(10,TimeUnit.SECONDS);
            var dead=KafkaTestUtils.getSingleRecord(dlt,"incidentops.incidents.v1.DLT",Duration.ofSeconds(30));
            assertThat(dead.value()).isEqualTo("{invalid-json");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?",Integer.class,event.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?",Integer.class,event.getId())).isEqualTo(1);
            assertThat(outbox.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
        }
    }
}
