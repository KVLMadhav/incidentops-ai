package com.incidentops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentops.catalog.*;
import com.incidentops.entity.*;
import com.incidentops.identity.team.*;
import com.incidentops.incident.*;
import com.incidentops.events.*;
import com.incidentops.repository.UserRepository;
import com.incidentops.config.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.MDC;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Import(SlaOperationsIntegrationTest.Time.class)
class SlaOperationsIntegrationTest {
    static final Instant START=Instant.parse("2026-11-06T00:00:00Z");
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(START);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now.get();}
        void advance(long seconds){now.updateAndGet(value->value.plusSeconds(seconds));}
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock controlledClock(){return new MutableClock();} }
    @Autowired MutableClock clock; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired UserRepository users; @Autowired TeamRepository teams; @Autowired TeamMemberRepository members;
    @Autowired MonitoredServiceRepository services; @Autowired IncidentService incidents; @Autowired IncidentRepository repository;
    @Autowired SlaService sla; @Autowired SLAPolicyService policies; @Autowired NotificationService notifications;
    @Autowired NotificationDeliveryWorker worker; @Autowired OperationalEvidence evidence; @Autowired ReliabilityCache cache;
    @Autowired PlatformTransactionManager manager; @Autowired SlaNotificationMetrics samples; @Autowired OperationsMetrics operations;
    @Autowired PrometheusMeterRegistry metrics;
    @MockitoSpyBean InAppDeliveryAdapter adapter;
    @MockitoSpyBean OutboxEventRepository outbox;
    User admin,viewer,responder,outsider,teamManager; Team team; MonitoredService service;
    @BeforeEach void seed(){
        reset(adapter,outbox);clock.now.set(START);MDC.clear();
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM outbox_events");
        for(String table:List.of("audit_logs","incident_status_history","incident_notes","incident_assignments","incident_alerts"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM monitored_services");
        jdbc.update("DELETE FROM team_members");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM sla_scheduler_failures");
        admin=users.save(new User("d4-admin@example.test","Admin","unused",Role.ADMIN));
        viewer=users.save(new User("d4-viewer@example.test","Viewer","unused",Role.VIEWER));
        responder=users.save(new User("d4-responder@example.test","Responder","unused",Role.RESPONDER));
        teamManager=users.save(new User("d4-manager@example.test","Manager","unused",Role.INCIDENT_MANAGER));
        outsider=users.save(new User("d4-outsider@example.test","Outsider","unused",Role.ADMIN));
        team=teams.save(new Team("Day4 controlled team"));
        members.saveAll(List.of(new TeamMember(team.getId(),admin.getId(),Role.ADMIN),new TeamMember(team.getId(),viewer.getId(),Role.VIEWER),
            new TeamMember(team.getId(),responder.getId(),Role.RESPONDER),new TeamMember(team.getId(),teamManager.getId(),Role.INCIDENT_MANAGER)));
        service=services.save(new MonitoredService(team.getId(),"Day4 checkout",null,null,"day4-checkout"));
    }
    IncidentDtos.IncidentResponse create(Severity severity){
        return incidents.create(admin.getId(),team.getId(),new IncidentDtos.CreateIncidentRequest(service.getId(),"Controlled Day4 incident",null,severity,List.of()));
    }
    void transition(String id,IncidentStatus status){
        incidents.transition(admin.getId(),team.getId(),id,new IncidentDtos.TransitionRequest(repository.findById(id).orElseThrow().getVersion(),status,"Controlled proof"));
    }
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    void consume(String eventId){
        notifications.process(jdbc.queryForObject("SELECT * FROM outbox_events WHERE id=?",(rs,n)->new IncidentEvent(eventId,1,rs.getString("event_type"),
            team.getId(),rs.getString("aggregate_id"),service.getId(),rs.getTimestamp("created_at").toInstant(),clock.instant(),rs.getString("correlation_id")),eventId));
    }
    String breachEvent(){return jdbc.queryForObject("SELECT event_id FROM sla_breaches ORDER BY target LIMIT 1",String.class);}
    String delivery(){return jdbc.queryForObject("SELECT id FROM notification_deliveries ORDER BY id LIMIT 1",String.class);}
    @Test void clockProvesBothDeadlineFormulasForEverySeverity(){
        long[] ack={300,900,3600,14400},resolve={3600,14400,43200,172800};int index=0;
        for(Severity severity:Severity.values()){
            var incident=create(severity);var instance=sla.instance(incident.id());
            assertThat(incident.createdAt()).isEqualTo(START);
            assertThat(instance.policyId()).isNotNull();assertThat(instance.severity()).isEqualTo(severity);
            assertThat(instance.targets()).anySatisfy(t->{if(t.target().equals("ACKNOWLEDGE"))assertThat(t.dueAt()).isEqualTo(START.plusSeconds(ack[severity.ordinal()]));else throw new AssertionError();});
            assertThat(instance.targets().stream().filter(t->t.target().equals("RESOLVE")).findFirst().orElseThrow().dueAt()).isEqualTo(START.plusSeconds(resolve[index]));
            index++;
        }
        clock.advance(299);assertThat(sla.scan()).isZero();clock.advance(2);assertThat(sla.scan()).isEqualTo(1);
    }
    @Test void acknowledgementBeforeAndExactlyAtDeadlineAvoidBreachButLateCompletionRetainsEvidence(){
        var before=create(Severity.SEV1);clock.advance(299);transition(before.id(),IncidentStatus.ACKNOWLEDGED);
        var exact=create(Severity.SEV1);clock.advance(300);transition(exact.id(),IncidentStatus.ACKNOWLEDGED);
        var late=create(Severity.SEV1);clock.advance(301);transition(late.id(),IncidentStatus.ACKNOWLEDGED);
        assertThat(sla.scan()).isEqualTo(1);assertThat(sla.scan()).isZero();
        assertThat(count("sla_breaches")).isEqualTo(1);
        assertThat(sla.targets(before.id()).stream().filter(t->t.target().equals("ACKNOWLEDGE")).findFirst().orElseThrow().breached()).isFalse();
        var target=sla.targets(late.id()).stream().filter(t->t.target().equals("ACKNOWLEDGE")).findFirst().orElseThrow();
        assertThat(target.completedAt()).isEqualTo(clock.instant());assertThat(target.breachedAt()).isEqualTo(clock.instant());
        assertThat(target.dueAt()).isEqualTo(START.plusSeconds(899));
    }
    @Test void resolutionBreachAndReopeningPreserveOriginalInstanceAndCompletion(){
        var incident=create(Severity.SEV1);String id=incident.id();var original=sla.instance(id);
        transition(id,IncidentStatus.ACKNOWLEDGED);transition(id,IncidentStatus.INVESTIGATING);
        clock.advance(1800);transition(id,IncidentStatus.MITIGATED);clock.advance(1801);transition(id,IncidentStatus.RESOLVED);
        assertThat(sla.scan()).isEqualTo(1);assertThat(breachEvent()).isNotBlank();
        var completed=sla.instance(id);transition(id,IncidentStatus.REOPENED);clock.advance(3600);
        assertThat(sla.instance(id)).isEqualTo(completed);assertThat(sla.scan()).isZero();
        assertThat(completed.id()).isEqualTo(original.id());assertThat(count("sla_breaches")).isEqualTo(1);
        transition(id,IncidentStatus.INVESTIGATING);transition(id,IncidentStatus.MITIGATED);transition(id,IncidentStatus.RESOLVED);transition(id,IncidentStatus.CLOSED);
        assertThat(count("sla_breaches")).isEqualTo(1);assertThat(sla.instance(id)).isEqualTo(completed);
    }
    @Test void twoSchedulerInstancesAndRepeatedConsumerDeliverOneLogicalBreachNotification() throws Exception {
        create(Severity.SEV1);clock.advance(301);
        var second=new SlaService(jdbc,clock,outbox,false,cache,policies,evidence,manager);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return sla.scan();});var b=pool.submit(()->{gate.await();return second.scan();});gate.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)+b.get(15,TimeUnit.SECONDS)).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(sla.scan()).isZero();String event=breachEvent();consume(event);consume(event);
        assertThat(count("sla_breaches")).isEqualTo(1);assertThat(count("incident_notifications")).isEqualTo(1);assertThat(count("notification_deliveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT notification_target FROM outbox_events WHERE id=?",String.class,event)).isEqualTo("TEAM_RESPONDERS_AND_MANAGERS");
    }
    @Test void oneFailureRetriesThenDeliveryHistoryAndEvidenceAgree() throws Exception {
        MDC.put("correlationId","day4-controlled-proof");
        var incident=create(Severity.SEV1);MDC.clear();clock.advance(301);assertThat(sla.scan()).isEqualTo(1);consume(breachEvent());
        doThrow(new IllegalStateException("secret error text must not be retained")).doNothing().when(adapter).deliver(anyString(),anyString(),anyString());
        assertThat(worker.runBatch()).isZero();assertThat(worker.runBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries",String.class)).isEqualTo("RETRY");
        clock.advance(2);assertThat(worker.runBatch()).isEqualTo(1);assertThat(worker.runBatch()).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM notification_delivery_attempts ORDER BY attempt",String.class)).containsExactly("FAILED","DELIVERED");
        assertThat(jdbc.queryForObject("SELECT error FROM notification_delivery_attempts WHERE attempt=1",String.class)).isEqualTo("IllegalStateException");
        samples.sample();operations.sample();String scrape=metrics.scrape();
        assertThat(scrape).contains("incidentops_sla_breaches{target=\"ACKNOWLEDGE\"} 1.0","incidentops_notifications_deliveries{status=\"DELIVERED\"} 1.0");
        assertThat(scrape).doesNotContain("day4-controlled-proof",incident.id(),admin.getId());
        assertThat(incidents.timeline(viewer.getId(),team.getId(),incident.id())).anySatisfy(t->assertThat(t.detail()).contains("day4-controlled-proof"));
        assertThat(incidents.audit(viewer.getId(),team.getId(),incident.id())).extracting(AuditLog::getAction).contains("SLA_BREACHED","NOTIFICATION_QUEUED","NOTIFICATION_RETRY","NOTIFICATION_DELIVERED");
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        System.out.println("DAY4_EVIDENCE correlation=day4-controlled-proof incident="+incident.id()+" breachEvent="+breachEvent()+" delivery="+delivery()+" attempts=FAILED,DELIVERED readiness=UP");
    }
    @Test void permanentFailureStopsAtThreeAttemptsAndIsVisibleToViewer() throws Exception {
        create(Severity.SEV1);clock.advance(301);sla.scan();consume(breachEvent());
        doThrow(new IllegalArgumentException("private")).when(adapter).deliver(anyString(),anyString(),anyString());
        worker.runBatch();clock.advance(2);worker.runBatch();clock.advance(4);worker.runBatch();clock.advance(100);worker.runBatch();
        assertThat(count("notification_delivery_attempts")).isEqualTo(3);verify(adapter,times(3)).deliver(anyString(),anyString(),anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries",String.class)).isEqualTo("DEAD_LETTER");
        mvc.perform(get(base()+"/notifications").with(jwt().jwt(j->j.subject(viewer.getId()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("DEAD_LETTER")).andExpect(jsonPath("$[0].attempts").value(3));
    }
    @Test void competingDeliveryWorkersCannotShareALiveClaim() throws Exception {
        create(Severity.SEV1);clock.advance(301);sla.scan();consume(breachEvent());String id=delivery();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");return null;})
            .when(adapter).deliver(eq(id),anyString(),anyString());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->worker.deliver(id));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.deliver(id)).isFalse();release.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(count("notification_delivery_attempts")).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void inactivePolicyAndInvalidDurationsCannotCreateAnUnprotectedIncident() throws Exception {
        policies.current(team.getId());
        policies.change(team.getId(),Severity.SEV1,new SLAPolicyService.Change(1L,"Disabled",300,3600,false));
        assertThatThrownBy(()->create(Severity.SEV1)).isInstanceOf(com.incidentops.exception.ApiException.class);
        assertThat(count("incidents")).isZero();assertThat(count("sla_instances")).isZero();
        mvc.perform(put(base()+"/sla/policies/SEV1").with(jwt().jwt(j->j.subject(admin.getId()))).contentType("application/json")
            .content(json.writeValueAsString(new SLAPolicyService.Change(2L,"Invalid",120,60,true))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SLA_DURATION"));
        policies.change(team.getId(),Severity.SEV1,new SLAPolicyService.Change(2L,"Enabled",300,3600,true));
        assertThat(sla.instance(create(Severity.SEV1).id()).policyVersion()).isEqualTo("policy-3");
    }
    @Test void expiredDeliveryClaimRetriesAndExpiredFinalAttemptBecomesDeadLetter(){
        create(Severity.SEV1);clock.advance(301);sla.scan();consume(breachEvent());String id=delivery();
        jdbc.update("UPDATE notification_deliveries SET status='PROCESSING',attempts=1,lease_owner=?,lease_until=? WHERE id=?",UUID.randomUUID().toString(),java.sql.Timestamp.from(clock.instant().plusSeconds(30)),id);
        jdbc.update("INSERT INTO notification_delivery_attempts(delivery_id,attempt,started_at,status) VALUES (?,1,?,'STARTED')",id,java.sql.Timestamp.from(clock.instant()));
        assertThat(worker.runBatch()).isZero();clock.advance(31);assertThat(worker.runBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error FROM notification_delivery_attempts WHERE delivery_id=? AND attempt=1",String.class,id)).isEqualTo("LeaseExpired");
        jdbc.update("UPDATE notification_deliveries SET status='PROCESSING',attempts=3,available_at=?,lease_until=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()),id);
        jdbc.update("INSERT INTO notification_delivery_attempts(delivery_id,attempt,started_at,status) VALUES (?,3,?,'STARTED')",id,java.sql.Timestamp.from(clock.instant()));
        assertThat(worker.runBatch()).isZero();assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE id=?",String.class,id)).isEqualTo("DEAD_LETTER");
    }
    @Test void schedulerFailureRollsBackAndDurablyRecordsFailureBeforeRetry(){
        create(Severity.SEV1);clock.advance(301);
        doThrow(new IllegalStateException("injected")).when(outbox).saveAndFlush(any(OutboxEvent.class));
        assertThatThrownBy(()->sla.scan()).isInstanceOf(IllegalStateException.class);
        assertThat(count("sla_breaches")).isZero();assertThat(count("sla_scheduler_failures")).isEqualTo(1);
        reset(outbox);assertThat(sla.scan()).isEqualTo(1);assertThat(sla.scan()).isZero();
    }
    @Test void incidentAndSlaSnapshotRollBackTogether(){
        doThrow(new IllegalStateException("injected")).when(outbox).saveAndFlush(any(OutboxEvent.class));
        assertThatThrownBy(()->create(Severity.SEV1)).isInstanceOf(IllegalStateException.class);
        assertThat(count("incidents")).isZero();assertThat(count("sla_instances")).isZero();assertThat(count("incident_sla_targets")).isZero();
    }
    @Test void policiesAreVersionedAndRolesAndTeamIsolationAreEnforced() throws Exception {
        var incident=create(Severity.SEV1);var original=sla.instance(incident.id());
        String body=json.writeValueAsString(new SLAPolicyService.Change(1L,"Updated SEV1",60,120,true));
        for(User denied:List.of(viewer,responder))mvc.perform(put(base()+"/sla/policies/SEV1").with(jwt().jwt(j->j.subject(denied.getId())))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(base()+"/sla/policies/SEV1").with(jwt().jwt(j->j.subject(teamManager.getId())))
            .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(base()+"/sla/policies/SEV1").with(jwt().jwt(j->j.subject(admin.getId())))
            .contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        assertThat(sla.instance(incident.id())).isEqualTo(original);
        assertThat(sla.targets(create(Severity.SEV1).id()).getFirst().dueAt()).isEqualTo(START.plusSeconds(60));
        for(String path:List.of("/sla/policies","/sla/active","/sla/breaches","/incidents/"+incident.id()+"/sla-instance")){
            mvc.perform(get(base()+path).with(jwt().jwt(j->j.subject(viewer.getId())))).andExpect(status().isOk());
            mvc.perform(get(base()+path).with(jwt().jwt(j->j.subject(outsider.getId())))).andExpect(status().isNotFound());
        }
        clock.advance(301);sla.scan();consume(breachEvent());String notice=jdbc.queryForObject("SELECT id FROM incident_notifications",String.class);
        for(String suffix:List.of("/deliveries","/attempts")){
            mvc.perform(get(base()+"/notifications/"+notice+suffix).with(jwt().jwt(j->j.subject(viewer.getId())))).andExpect(status().isOk());
            mvc.perform(get(base()+"/notifications/"+notice+suffix).with(jwt().jwt(j->j.subject(outsider.getId())))).andExpect(status().isNotFound());
        }
        mvc.perform(post(base()+"/notifications/"+notice+"/deliveries").with(jwt().jwt(j->j.subject(viewer.getId())))).andExpect(status().isMethodNotAllowed());
    }
    String base(){return "/api/v1/teams/"+team.getId();}
}
