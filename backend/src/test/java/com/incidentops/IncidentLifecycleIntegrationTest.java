package com.incidentops;

import com.fasterxml.jackson.databind.*;
import com.incidentops.catalog.*;
import com.incidentops.entity.*;
import com.incidentops.identity.team.*;
import com.incidentops.incident.*;
import com.incidentops.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class IncidentLifecycleIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Autowired UserRepository users; @Autowired AuthSessionRepository sessions;
 @Autowired TeamRepository teams; @Autowired TeamMemberRepository members; @Autowired MonitoredServiceRepository services;
 @Autowired AlertRepository alerts; @Autowired IncidentRepository incidents; @Autowired IncidentAlertRepository links;
 @Autowired IncidentAssignmentRepository assignments; @Autowired IncidentNoteRepository notes;
 @Autowired IncidentStatusHistoryRepository history; @Autowired AuditLogRepository audits;
 @MockitoSpyBean OutboxEventRepository outbox;
 @Autowired PasswordEncoder passwords; @Autowired JwtEncoder encoder; @Autowired IncidentService service;
 @Autowired SlaService sla;
 @Autowired TeamService teamService;
 @Autowired com.incidentops.events.NotificationService notifications;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 User admin, responder, viewer, outsider; Team team, other; MonitoredService monitored, otherService;
 Map<String,String> tokens=new HashMap<>();
 @BeforeEach void seed(){
  Mockito.reset(outbox);
  jdbc.update("DELETE FROM processed_events");
  outbox.deleteAll();audits.deleteAll();history.deleteAll();notes.deleteAll();assignments.deleteAll();links.deleteAll();
  incidents.deleteAll();alerts.deleteAll();services.deleteAll();members.deleteAll();teams.deleteAll();sessions.deleteAll();users.deleteAll();
  String hash=passwords.encode("Test-password-123!");
  admin=users.save(new User("day2-admin@example.test","Admin",hash,Role.ADMIN));
  responder=users.save(new User("day2-responder@example.test","Responder",hash,Role.RESPONDER));
  viewer=users.save(new User("day2-viewer@example.test","Viewer",hash,Role.VIEWER));
  outsider=users.save(new User("day2-outsider@example.test","Outsider",hash,Role.ADMIN));
  team=teams.save(new Team("Payments Day2"));other=teams.save(new Team("Shipping Day2"));
  members.saveAll(List.of(new TeamMember(team.getId(),admin.getId(),Role.ADMIN),
   new TeamMember(team.getId(),responder.getId(),Role.RESPONDER),new TeamMember(team.getId(),viewer.getId(),Role.VIEWER),
   new TeamMember(other.getId(),outsider.getId(),Role.ADMIN)));
  monitored=services.save(new MonitoredService(team.getId(),"Checkout Day2",null,null,"checkout-day2"));
  otherService=services.save(new MonitoredService(other.getId(),"Shipping Day2",null,null,"shipping-day2"));
  tokens.clear();
 }
 @Test void completeLifecycleInvalidTransitionAndStaleVersion() throws Exception {
  String alert=submitAlert();
  JsonNode created=createIncident(List.of(alert));
  String id=created.get("id").asText();
  assertThat(created.get("incidentNumber").asText()).matches("INC-[0-9]{4}-[0-9]{6,}");
  assertThat(created.get("status").asText()).isEqualTo("OPEN");
  assertThat(history.findByTeamIdAndIncidentIdOrderByChangedAtAscIdAsc(team.getId(),id)).hasSize(1);
  assertThat(audits.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).hasSize(1);
  assertThat(links.findByTeamIdAndIncidentIdOrderByLinkedAtAscIdAsc(team.getId(),id)).hasSize(1);
  assertThat(outbox.count()).isEqualTo(1);
  String secondAlert=submitAlert();
  result(post(path(id)+"/alerts"),admin,Map.of("alertId",secondAlert),201);
  assertThat(links.findByTeamIdAndIncidentIdOrderByLinkedAtAscIdAsc(team.getId(),id)).hasSize(2);
  long version=created.get("version").asLong();
  mvc.perform(post(path(id)+"/transitions").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",version,"status","CLOSED"))))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
   .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ACKNOWLEDGED")));
  assertThat(incidents.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.OPEN);
  JsonNode assigned=result(post(path(id)+"/assignment"),admin,Map.of("version",version,"assigneeUserId",responder.getId(),"reason","On call"),200);
  assertThat(assigned.get("version").asLong()).isEqualTo(version+1);
  mvc.perform(post(path(id)+"/assignment").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",version,"assigneeUserId",admin.getId()))))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
  assertThat(incidents.findById(id).orElseThrow().getAssigneeUserId()).isEqualTo(responder.getId());
  JsonNode edited=result(put(path(id)+"/details"),admin,Map.of("version",assigned.get("version").asLong(),
   "title","Checkout outage in region","description","Customers cannot pay","severity","SEV1"),200);
  assertThat(edited.get("title").asText()).isEqualTo("Checkout outage in region");
  JsonNode reassigned=result(post(path(id)+"/assignment"),admin,Map.of("version",edited.get("version").asLong(),
   "assigneeUserId",admin.getId(),"reason","Backup coverage"),200);
  result(post(path(id)+"/assignment"),admin,Map.of("version",reassigned.get("version").asLong(),
   "assigneeUserId",responder.getId(),"reason","Primary returned"),200);
  var assignmentHistory=assignments.findByTeamIdAndIncidentIdOrderByAssignedAtAscIdAsc(team.getId(),id);
  assertThat(assignmentHistory).hasSize(3);
  assertThat(assignmentHistory).anySatisfy(e->{assertThat(e.getAssigneeUserId()).isEqualTo(admin.getId());
   assertThat(e.getPreviousAssigneeUserId()).isEqualTo(responder.getId());
   assertThat(e.getReason()).isEqualTo("Backup coverage");});
  assertThat(assignmentHistory).anySatisfy(e->{assertThat(e.getPreviousAssigneeUserId()).isEqualTo(admin.getId());
   assertThat(e.getAssigneeUserId()).isEqualTo(responder.getId());
   assertThat(e.getReason()).isEqualTo("Primary returned");});
  result(post(path(id)+"/notes"),responder,Map.of("body","Checkout requests failing in one region"),201);
  for(String next:List.of("ACKNOWLEDGED","INVESTIGATING","MITIGATED","RESOLVED","REOPENED","INVESTIGATING","MITIGATED","RESOLVED","CLOSED")){
   long current=incidents.findById(id).orElseThrow().getVersion();
   result(post(path(id)+"/transitions"),responder,Map.of("version",current,"status",next,"reason","Demo"),200);
  }
  assertThat(incidents.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.CLOSED);
  mvc.perform(get(path(id)+"/timeline").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("STATUS"))
   .andExpect(jsonPath("$[?(@.type == 'ALERT_LINK')]").exists())
   .andExpect(jsonPath("$[?(@.type == 'NOTE')]").exists());
  mvc.perform(get(path(id)+"/audit").header("Authorization",bearer(viewer))).andExpect(status().isOk());
  assertThat(history.findByTeamIdAndIncidentIdOrderByChangedAtAscIdAsc(team.getId(),id)).hasSize(10);
  assertThat(assignments.findByTeamIdAndIncidentIdOrderByAssignedAtAscIdAsc(team.getId(),id)).hasSize(3);
 }
 @Test void rolesCrossTeamAndPaginationAreEnforced() throws Exception {
  String alert=submitAlert(); JsonNode incident=createIncident(List.of(alert));String id=incident.get("id").asText();
  mvc.perform(post(path(id)+"/notes").header("Authorization",bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("body","not allowed")))).andExpect(status().isForbidden());
  mvc.perform(post(path(id)+"/transitions").header("Authorization",bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",0,"status","ACKNOWLEDGED")))).andExpect(status().isForbidden());
  mvc.perform(post(path(id)+"/assignment").header("Authorization",bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",0,"assigneeUserId",viewer.getId())))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/teams/"+team.getId()+"/alerts").header("Authorization",bearer(viewer)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("serviceId",monitored.getId(),"source","test","externalAlertId","viewer-alert",
    "title","Forbidden","severity","SEV2","occurredAt",Instant.now().toString())))).andExpect(status().isForbidden());
  mvc.perform(post(path(id)+"/assignment").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",0,"assigneeUserId",viewer.getId()))))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));
  mvc.perform(get("/api/v1/teams/"+other.getId()+"/incidents/"+id).header("Authorization",bearer(outsider)))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
  String guessedPath="/api/v1/teams/"+other.getId()+"/incidents/"+id;
  for (String suffix : List.of("/timeline", "/audit")) {
   mvc.perform(get(guessedPath+suffix).header("Authorization",bearer(outsider)))
    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
  }
  mvc.perform(put(guessedPath+"/details").header("Authorization",bearer(outsider)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",0,"title","Cross-team edit","severity","SEV4"))))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
  mvc.perform(post(path(id)+"/transitions").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("version",-1,"status","ACKNOWLEDGED"))))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
  mvc.perform(get("/api/v1/teams/"+other.getId()+"/alerts").header("Authorization",bearer(viewer)))
   .andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/members?size=2").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
   .andExpect(jsonPath("$.totalElements").value(3));
  mvc.perform(get("/api/v1/teams/"+other.getId()+"/members").header("Authorization",bearer(viewer)))
   .andExpect(status().isNotFound());
  String foreignAlert=alerts.saveAndFlush(new Alert(other.getId(),otherService.getId(),"prometheus","foreign-alert",
   "Other team signal",null,Severity.SEV2,Instant.now(),"{}",Instant.now())).getId();
  mvc.perform(post(path(id)+"/alerts").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
   .content(body(Map.of("alertId",foreignAlert)))).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/teams/"+team.getId()+"/incidents").header("Authorization",bearer(admin))
   .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("serviceId",otherService.getId(),"title","Wrong service","severity","SEV2"))))
   .andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/incidents?size=101").header("Authorization",bearer(viewer)))
   .andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/incidents?status=OPEN&size=1").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
   .andExpect(jsonPath("$.totalElements").value(1));
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/alerts?severity=SEV1&size=1").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  result(post(path(id)+"/assignment"),admin,Map.of("version",0,"assigneeUserId",responder.getId()),200);
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/incidents?serviceId="+monitored.getId()
   +"&assigneeUserId="+responder.getId()+"&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z")
   .header("Authorization",bearer(viewer))).andExpect(status().isOk())
   .andExpect(jsonPath("$.totalElements").value(1));
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/alerts?serviceId="+monitored.getId()
   +"&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z")
   .header("Authorization",bearer(viewer))).andExpect(status().isOk())
   .andExpect(jsonPath("$.totalElements").value(1));
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/incidents?from=2030-01-01T00:00:00Z&to=2020-01-01T00:00:00Z")
   .header("Authorization",bearer(viewer))).andExpect(status().isBadRequest())
   .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
 }
 @Test void concurrentDetailUpdatesWithSameVersionCommitOnlyOneChange() throws Exception {
  JsonNode created = createIncident(List.of());
  String id = created.get("id").asText();
  long version = created.get("version").asLong();
  String authorization = bearer(admin);
  CountDownLatch start = new CountDownLatch(1);
  try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
   java.util.function.Function<String, Future<org.springframework.mock.web.MockHttpServletResponse>> edit = title ->
    pool.submit(() -> {
     start.await();
     return mvc.perform(put(path(id)+"/details").header("Authorization",authorization)
      .contentType(MediaType.APPLICATION_JSON)
      .content(body(Map.of("version",version,"title",title,"severity","SEV1"))))
      .andReturn().getResponse();
    });
   var first = edit.apply("Checkout outage in Europe");
   var second = edit.apply("Checkout outage in Asia");
   start.countDown();
   var a = first.get(15,TimeUnit.SECONDS);
   var b = second.get(15,TimeUnit.SECONDS);
   assertThat(List.of(a.getStatus(),b.getStatus())).containsExactlyInAnyOrder(200,409);
   var winner = json.readTree((a.getStatus()==200?a:b).getContentAsString());
   var rejected = json.readTree((a.getStatus()==409?a:b).getContentAsString());
   assertThat(rejected.get("code").asText()).isEqualTo("STALE_VERSION");
   var stored = incidents.findById(id).orElseThrow();
   assertThat(stored.getTitle()).isEqualTo(winner.get("title").asText());
   assertThat(stored.getVersion()).isEqualTo(version+1);
   assertThat(audits.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id))
    .filteredOn(entry -> entry.getAction().equals("DETAILS_UPDATED")).hasSize(1);
  }
 }
 @Test void failedOutboxWriteRollsBackEntireCreation() throws Exception {
  String alert=submitAlert();
  Mockito.doThrow(new IllegalStateException("forced outbox failure")).when(outbox).saveAndFlush(any(OutboxEvent.class));
  mvc.perform(post("/api/v1/teams/"+team.getId()+"/incidents").header("Authorization",bearer(admin))
   .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("serviceId",monitored.getId(),"title","Will roll back",
    "severity","SEV1","alertIds",List.of(alert)))))
   .andExpect(status().isInternalServerError());
  assertThat(incidents.count()).isZero();assertThat(history.count()).isZero();assertThat(audits.count()).isZero();
  assertThat(links.count()).isZero();assertThat(outbox.count()).isZero();
  assertThat(alerts.count()).isEqualTo(1);
 }
 @Test void simultaneousCreatorsGetDistinctDatabaseSequenceNumbers() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try {
   CountDownLatch start=new CountDownLatch(1);
   Callable<String> task=()->{start.await();return service.create(admin.getId(),team.getId(),
    new com.incidentops.incident.IncidentDtos.CreateIncidentRequest(monitored.getId(),"Concurrent",null,Severity.SEV2,List.of())).incidentNumber();};
   Future<String> one=pool.submit(task),two=pool.submit(task);start.countDown();
   assertThat(one.get(15,TimeUnit.SECONDS)).isNotEqualTo(two.get(15,TimeUnit.SECONDS));
   assertThat(jdbc.queryForObject("select count(distinct incident_number) from incidents",Integer.class)).isEqualTo(2);
  } finally {pool.shutdownNow();}
 }
 @Test void commandRetriesReturnOriginalResultWithoutDuplicateEffects() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  String command=UUID.randomUUID().toString();
  var assignment=Map.of("version",0,"assigneeUserId",responder.getId(),"commandId",command);
  JsonNode one=result(post(path(id)+"/assignment"),admin,assignment,200);
  assertThat(result(post(path(id)+"/assignment"),admin,assignment,200)).isEqualTo(one);
  assertThat(assignments.findByTeamIdAndIncidentIdOrderByAssignedAtAscIdAsc(team.getId(),id)).hasSize(1);
  result(post(path(id)+"/assignment"),admin,Map.of("version",0,"assigneeUserId",admin.getId(),"commandId",command),409);
  var transition=Map.of("version",1,"status","ACKNOWLEDGED","commandId",UUID.randomUUID().toString());
  JsonNode moved=result(post(path(id)+"/transitions"),responder,transition,200);
  assertThat(result(post(path(id)+"/transitions"),responder,transition,200)).isEqualTo(moved);
  var note=Map.of("body","Investigating database connections","commandId",UUID.randomUUID().toString());
  JsonNode added=result(post(path(id)+"/notes"),responder,note,201);
  assertThat(result(post(path(id)+"/notes"),responder,note,201)).isEqualTo(added);
  assertThat(notes.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).hasSize(1);
  assertThat(outbox.count()).isEqualTo(4);
 }
 @Test void responderMustBeAssignedAndResolutionNeedsExplanation() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  result(post(path(id)+"/transitions"),responder,Map.of("version",0,"status","ACKNOWLEDGED"),403);
  result(post(path(id)+"/notes"),responder,Map.of("body","Not assigned"),403);
  result(post(path(id)+"/assignment"),admin,Map.of("version",0,"assigneeUserId",responder.getId()),200);
  for(String next:List.of("ACKNOWLEDGED","INVESTIGATING","MITIGATED"))
   result(post(path(id)+"/transitions"),responder,Map.of("version",incidents.findById(id).orElseThrow().getVersion(),"status",next),200);
  result(post(path(id)+"/transitions"),responder,Map.of("version",incidents.findById(id).orElseThrow().getVersion(),"status","RESOLVED"),400);
 }
 @Test void lateCompletionIsStillABreachAndScanningIsIdempotent() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  assertThat(sla.targets(id)).hasSize(3);
  Instant past=Instant.now().minusSeconds(600);
  jdbc.update("UPDATE incident_sla_targets SET due_at=? WHERE incident_id=? AND target='ACKNOWLEDGE'",java.sql.Timestamp.from(past),id);
  result(post(path(id)+"/transitions"),admin,Map.of("version",0,"status","ACKNOWLEDGED"),200);
  assertThat(sla.targets(id).getFirst().completedAt()).isNotNull();
  assertThat(sla.targets(id).getFirst().breached()).isTrue();
  assertThat(sla.scan()).isEqualTo(1);
  assertThat(sla.scan()).isZero();
  assertThat(audits.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).filteredOn(a->a.getAction().equals("SLA_BREACHED")).hasSize(1);
  mvc.perform(get(path(id)+"/sla").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].breached").value(true));
 }
 @Test void slaCatchesUpAfterDowntimeAndKeepsSeveritySnapshot() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  var original=sla.targets(id);
  result(put(path(id)+"/details"),admin,Map.of("version",0,"title","Changed severity","severity","SEV4"),200);
  assertThat(sla.targets(id)).isEqualTo(original);
  jdbc.update("UPDATE incident_sla_targets SET due_at=? WHERE incident_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),id);
  assertThat(sla.scan()).isEqualTo(3);
  assertThat(sla.scan()).isZero();
 }
 @Test void concurrentIdenticalCommandsProduceOneNote() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  var request=new com.incidentops.incident.IncidentDtos.NoteRequest("One effect",UUID.randomUUID().toString());
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try {
   var gate=new CountDownLatch(1);
   Callable<String> action=()->{gate.await();return service.note(admin.getId(),team.getId(),id,request).id();};
   var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
   assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
   assertThat(notes.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).hasSize(1);
  }finally{pool.shutdownNow();}
 }
 @Test void notificationRedeliveryCommitsOneReceiptAndOneEffect() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  var event=new com.incidentops.events.IncidentEvent(UUID.randomUUID().toString(),1,"INCIDENT_CREATED",team.getId(),id,monitored.getId(),Instant.now(),Instant.now(),UUID.randomUUID().toString());
  notifications.process(event);notifications.process(event);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE incident_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events",Integer.class)).isEqualTo(1);
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/notifications").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/notifications").header("Authorization",bearer(outsider))).andExpect(status().isNotFound());
 }
 @Test void failedNotificationEffectRollsBackReceipt() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  var event=new com.incidentops.events.IncidentEvent(UUID.randomUUID().toString(),1,"INCIDENT_CREATED",other.getId(),id,monitored.getId(),Instant.now(),Instant.now(),UUID.randomUUID().toString());
  org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.process(event)).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications",Integer.class)).isZero();
 }
 @Test void kafkaOutageRetainsOutboxAndExpiredLeaseCanRecover() throws Exception {
  createIncident(List.of());
  var kafka=Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
  Mockito.when(kafka.send(any(String.class),any(String.class),any(String.class)))
   .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
  var publisher=new com.incidentops.events.OutboxPublisher(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactionManager),
   kafka,json,java.time.Clock.systemUTC(),"test.events",false,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  String event=outbox.findAll().getFirst().getId();
  assertThat(publisher.publishOne(event)).isFalse();
  assertThat(outbox.findById(event).orElseThrow().getPublishedAt()).isNull();
  assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,event)).isEqualTo(1);
  Mockito.when(kafka.send(any(String.class),any(String.class),any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
  jdbc.update("UPDATE outbox_events SET available_at=?,lease_until=?,lease_owner=? WHERE id=?",
   java.sql.Timestamp.from(Instant.now().minusSeconds(120)),java.sql.Timestamp.from(Instant.now().plusSeconds(120)),UUID.randomUUID().toString(),event);
  assertThat(publisher.publishOne(event)).isFalse();
  jdbc.update("UPDATE outbox_events SET lease_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),event);
  assertThat(publisher.publishOne(event)).isTrue();
  assertThat(outbox.findById(event).orElseThrow().getPublishedAt()).isNotNull();
 }
 @Test void redisFailureFallsBackAndAuthorizationPrecedesCacheRead() throws Exception {
  createIncident(List.of());
  var redis=Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
  Mockito.when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
  var reads=new TeamReadService(teamService,jdbc,java.time.Clock.systemUTC(),new ReliabilityCache(redis,json,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true));
  assertThat(reads.summary(viewer.getId(),team.getId()).total()).isEqualTo(1);
  Mockito.clearInvocations(redis);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->reads.summary(outsider.getId(),team.getId()))
   .isInstanceOf(com.incidentops.exception.ApiException.class);
  Mockito.verifyNoInteractions(redis);
 }
 @Test void savedCommandCannotBeReplayedAfterMembershipRevocation() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  var request=Map.of("body","One authorized response note","commandId",UUID.randomUUID().toString());
  result(post(path(id)+"/notes"),admin,request,201);
  jdbc.update("DELETE FROM team_members WHERE team_id=? AND user_id=?",team.getId(),admin.getId());
  result(post(path(id)+"/notes"),admin,request,404);
  assertThat(notes.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).hasSize(1);
  assertThat(outbox.count()).isEqualTo(2);
 }
 @Test void changedNotePayloadCannotReuseAnExistingCommandIdentifier() throws Exception {
  String id=createIncident(List.of()).get("id").asText();
  String command=UUID.randomUUID().toString();
  result(post(path(id)+"/notes"),admin,Map.of("body","Original note","commandId",command),201);
  result(post(path(id)+"/notes"),admin,Map.of("body","Changed note","commandId",command),409);
  assertThat(notes.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team.getId(),id)).singleElement()
   .satisfies(note->assertThat(note.getBody()).isEqualTo("Original note"));
  assertThat(outbox.count()).isEqualTo(2);
 }
 @Test void metricsAreDeniedOnApplicationPortAndCorrelationIdIsReturned() throws Exception {
  mvc.perform(get("/actuator/prometheus").header("Authorization",bearer(admin))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/summary").header("Authorization",bearer(viewer)).header("X-Correlation-ID","ops-check"))
   .andExpect(status().isOk()).andExpect(header().string("X-Correlation-ID","ops-check"));
 }
 @Test void exactAlertRetriesAreEquivalentAndConflictsAreDurablyAudited() throws Exception {
  var request=new LinkedHashMap<String,Object>();request.put("serviceId",monitored.getId());request.put("source","prometheus");
  request.put("externalAlertId","retry-proof");request.put("title","Original evidence");request.put("severity","SEV2");
  request.put("occurredAt","2026-11-05T04:00:00.123456789Z");request.put("metadata",Map.of("a","1","b","2"));
  String url="/api/v1/teams/"+team.getId()+"/alerts";
  var first=result(post(url),admin,request,201);
  request.put("metadata",new LinkedHashMap<>(Map.of("b","2","a","1")));
  assertThat(result(post(url),admin,request,201).get("id")).isEqualTo(first.get("id"));
  assertThat(alerts.count()).isEqualTo(1);
  request.put("title","Conflicting evidence");
  assertThat(result(post(url),admin,request,409).get("code").asText()).isEqualTo("ALERT_IDENTITY_CONFLICT");
  assertThat(alerts.findAll().getFirst().getTitle()).isEqualTo("Original evidence");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_retry_conflicts WHERE alert_id=? AND differing_fields='title'",Integer.class,first.get("id").asText())).isEqualTo(1);
  // Same external ID from a different source is a real alert.
  request.put("source","cloudwatch");result(post(url),admin,request,201);assertThat(alerts.count()).isEqualTo(2);
  request.put("source","prometheus");request.put("serviceId",otherService.getId());
  result(post("/api/v1/teams/"+other.getId()+"/alerts"),outsider,request,201);assertThat(alerts.count()).isEqualTo(3);
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/delivery/alert-conflicts").header("Authorization",bearer(viewer))).andExpect(status().isForbidden());
 }
 @Test void concurrentAlertRetriesCreateOneIdentity() throws Exception {
  var request=new IncidentDtos.CreateAlertRequest(monitored.getId(),"test","concurrent","One observation",null,Severity.SEV2,Instant.now(),json.createObjectNode());
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {
   Callable<String> action=()->{gate.await();return service.submitAlert(admin.getId(),team.getId(),request).id();};
   var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
   assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));assertThat(alerts.count()).isEqualTo(1);
   org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("""
    INSERT INTO alerts(id,team_id,service_id,source,external_alert_id,title,description,severity,occurred_at,received_at,metadata)
    SELECT ?,team_id,service_id,source,external_alert_id,title,description,severity,occurred_at,received_at,metadata FROM alerts
    """,UUID.randomUUID().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }finally{pool.shutdownNow();}
 }
 @Test void explicitFingerprintLinksDistinctAlertsButSimilarTitlesDoNot() throws Exception {
  var metadata=json.readTree("{\"alertType\":\" HTTP_ERRORS \",\"stableLabels\":{\"region\":\"south\",\"cluster\":\"prod\"}}");
  var first=service.submitAlert(admin.getId(),team.getId(),new IncidentDtos.CreateAlertRequest(monitored.getId(),"test","group-1","Errors",null,Severity.SEV1,Instant.now(),metadata));
  var incident=service.create(admin.getId(),team.getId(),new IncidentDtos.CreateIncidentRequest(monitored.getId(),"Group target",null,Severity.SEV1,List.of(first.id())));
  var second=service.submitAlert(admin.getId(),team.getId(),new IncidentDtos.CreateAlertRequest(monitored.getId(),"test","group-2","Different title",null,Severity.SEV2,Instant.now(),
   json.readTree("{\"stableLabels\":{\"cluster\":\"prod\",\"region\":\"south\"},\"alertType\":\"http_errors\"}")));
  service.submitAlert(admin.getId(),team.getId(),new IncidentDtos.CreateAlertRequest(monitored.getId(),"test","unrelated","Errors",null,Severity.SEV1,Instant.now(),json.createObjectNode()));
  assertThat(first.id()).isNotEqualTo(second.id());assertThat(alerts.count()).isEqualTo(3);
  assertThat(links.findByTeamIdAndIncidentIdOrderByLinkedAtAscIdAsc(team.getId(),incident.id())).hasSize(2);
  service.create(admin.getId(),team.getId(),new IncidentDtos.CreateIncidentRequest(monitored.getId(),"Ambiguous target",null,Severity.SEV1,List.of(first.id())));
  var ambiguous=service.submitAlert(admin.getId(),team.getId(),new IncidentDtos.CreateAlertRequest(monitored.getId(),"test","group-3","Errors",null,Severity.SEV1,Instant.now(),metadata));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_alerts WHERE alert_id=?",Integer.class,ambiguous.id())).isZero();
 }
 @Test void failedBusinessEffectRollsBackAlreadyInsertedProcessedMarker() throws Exception {
  String id=createIncident(List.of()).get("id").asText();String eventId=UUID.randomUUID().toString();
  var event=new com.incidentops.events.IncidentEvent(eventId,1,"INCIDENT_CREATED",team.getId(),id,monitored.getId(),Instant.now(),Instant.now(),eventId);
  jdbc.update("INSERT INTO incident_notifications(id,team_id,incident_id,event_id,event_type,message,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
   UUID.randomUUID().toString(),team.getId(),id,eventId,"INCIDENT_CREATED","Force unique effect failure");
  org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.process(event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?",Integer.class,eventId)).isZero();
  jdbc.update("DELETE FROM incident_notifications WHERE event_id=?",eventId);
  notifications.process(event);notifications.process(event);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);
 }
 @Test void boundedOutboxAttemptsHaveVisibleEvidenceAndAuthorizedReplay() throws Exception {
  createIncident(List.of());String id=outbox.findAll().getFirst().getId();
  var kafka=Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
  Mockito.when(kafka.send(any(String.class),any(String.class),any(String.class))).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("poison")));
  var publisher=new com.incidentops.events.OutboxPublisher(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactionManager),kafka,json,java.time.Clock.systemUTC(),"test.events",false,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  for(int attempt=1;attempt<=5;attempt++) {
   jdbc.update("UPDATE outbox_events SET available_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);
   assertThat(publisher.publishOne(id)).isFalse();
  }
  assertThat(publisher.publishOne(id)).isFalse();Mockito.verify(kafka,Mockito.times(5)).send(any(String.class),any(String.class),any(String.class));
  assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id=? AND dead_lettered_at IS NOT NULL",String.class,id)).isEqualTo("IllegalArgumentException");
  String url="/api/v1/teams/"+team.getId()+"/delivery";
  mvc.perform(get(url+"/dead-letters").header("Authorization",bearer(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
  result(post(url+"/outbox/"+id+"/replay"),viewer,Map.of("reason","Fixed cause"),403);
  result(post(url+"/outbox/"+id+"/replay"),outsider,Map.of("reason","Fixed cause"),404);
  result(post(url+"/outbox/"+id+"/replay"),admin,Map.of("reason","Fixed cause"),200);
  assertThat(jdbc.queryForObject("SELECT previous_attempts FROM outbox_replays WHERE event_id=?",Integer.class,id)).isEqualTo(5);
  Mockito.when(kafka.send(any(String.class),any(String.class),any(String.class))).thenReturn(CompletableFuture.completedFuture(null));
  assertThat(publisher.publishOne(id)).isTrue();
 }
 @Test void concurrentOutboxWorkersCannotShareALiveClaimAndUncommittedWorkCannotPublish() throws Exception {
  createIncident(List.of());String id=outbox.findAll().getFirst().getId();
  var kafka=Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);var sent=new CountDownLatch(1);
  var ack=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
  Mockito.when(kafka.send(any(String.class),any(String.class),any(String.class))).thenAnswer(call->{sent.countDown();return ack;});
  var publisher=new com.incidentops.events.OutboxPublisher(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactionManager),kafka,json,java.time.Clock.systemUTC(),"test.events",false,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  var pool=Executors.newSingleThreadExecutor();
  try {
   var first=pool.submit(()->publisher.publishOne(id));
   if(!sent.await(10,TimeUnit.SECONDS))assertThat(first.get(1,TimeUnit.SECONDS)).as("Worker should have reached Kafka send").isTrue();
   assertThat(publisher.publishOne(id)).isFalse();ack.complete(null);assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
   var wire=org.mockito.ArgumentCaptor.forClass(String.class);
   String aggregateId=outbox.findById(id).orElseThrow().getAggregateId();
   Mockito.verify(kafka,Mockito.times(1)).send(org.mockito.ArgumentMatchers.eq("test.events"),org.mockito.ArgumentMatchers.eq(aggregateId),wire.capture());
   JsonNode envelope=json.readTree(wire.getValue());
   assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
   assertThat(envelope.get("serviceId").asText()).isEqualTo(monitored.getId());
   assertThat(envelope.get("correlationId").asText()).isNotBlank();
   assertThat(envelope.has("payload")).isFalse();assertThat(envelope.has("metadata")).isFalse();
   org.assertj.core.api.Assertions.assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{publisher.publishOne(id);return null;})).isInstanceOf(IllegalStateException.class);
  }finally{ack.complete(null);pool.shutdownNow();}
 }
 @Test void finalExpiredClaimMovesToDeadLetterWithoutAnotherSend() throws Exception {
  createIncident(List.of());String id=outbox.findAll().getFirst().getId();
  jdbc.update("UPDATE outbox_events SET attempts=5,lease_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);
  var kafka=Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
  var publisher=new com.incidentops.events.OutboxPublisher(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactionManager),kafka,json,java.time.Clock.systemUTC(),"test.events",false,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  assertThat(publisher.publishOne(id)).isFalse();Mockito.verifyNoInteractions(kafka);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id=? AND dead_lettered_at IS NOT NULL",Integer.class,id)).isEqualTo(1);
 }
 @Test void cachePopulationAndInvalidationWaitForCommitAndIgnoreRollback() {
  var redis=Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
  @SuppressWarnings("unchecked") var values=(org.springframework.data.redis.core.ValueOperations<String,String>)Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
  Mockito.when(redis.opsForValue()).thenReturn(values);Mockito.clearInvocations(redis);
  var cache=new ReliabilityCache(redis,json,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),true);
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  tx.execute(status->{cache.putAfterCommit("test-key",Map.of("ok",true));cache.invalidateAfterCommit(team.getId(),"incident");Mockito.verifyNoInteractions(redis,values);status.setRollbackOnly();return null;});
  Mockito.verifyNoInteractions(redis,values);
  tx.execute(status->{cache.putAfterCommit("test-key",Map.of("ok",true));cache.invalidateAfterCommit(team.getId(),"incident");Mockito.verifyNoInteractions(redis,values);return null;});
  Mockito.verify(redis).delete(org.mockito.ArgumentMatchers.<String>anyCollection());
  Mockito.verify(values).set(org.mockito.ArgumentMatchers.eq("test-key"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(java.time.Duration.ofSeconds(10)));
 }
 private String submitAlert() throws Exception {
  JsonNode a=result(post("/api/v1/teams/"+team.getId()+"/alerts"),responder,Map.of(
   "serviceId",monitored.getId(),"source","prometheus","externalAlertId",UUID.randomUUID().toString(),
   "title","Checkout error rate","severity","SEV1","occurredAt",Instant.now().toString(),
   "description","Error rate above threshold","metadata",Map.of("region","ap-south-1")),201);
  return a.get("id").asText();
 }
 private JsonNode createIncident(List<String> alertIds) throws Exception {
  return result(post("/api/v1/teams/"+team.getId()+"/incidents"),admin,Map.of(
   "serviceId",monitored.getId(),"title","Checkout failing","description","Customers cannot pay",
   "severity","SEV1","alertIds",alertIds),201);
 }
 private String path(String id){return "/api/v1/teams/"+team.getId()+"/incidents/"+id;}
 private JsonNode result(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,User actor,Object payload,int expected) throws Exception {
  String raw=mvc.perform(builder.header("Authorization",bearer(actor)).contentType(MediaType.APPLICATION_JSON).content(body(payload)))
   .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
  return json.readTree(raw);
 }
 private String bearer(User user){
  return "Bearer "+tokens.computeIfAbsent(user.getId(),ignored->{
   Instant now=Instant.now();var session=sessions.saveAndFlush(new AuthSession(user.getId(),now.plusSeconds(3600)));
   var claims=JwtClaimsSet.builder().issuer("incident-ops").audience(List.of("incident-ops-api")).subject(user.getId())
    .claim("sid",session.getId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900)).id(UUID.randomUUID().toString()).build();
   return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  });
 }
 private String body(Object o) throws Exception{return json.writeValueAsString(o);}
}
