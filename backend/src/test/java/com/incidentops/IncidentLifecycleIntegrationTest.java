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
 @Autowired TeamService teamService;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 User admin, responder, viewer, outsider; Team team, other; MonitoredService monitored, otherService;
 Map<String,String> tokens=new HashMap<>();
 @BeforeEach void seed(){
  Mockito.reset(outbox);
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
  assertThat(history.findByIncidentIdOrderByChangedAtAscIdAsc(id)).hasSize(1);
  assertThat(audits.findByIncidentIdOrderByCreatedAtAscIdAsc(id)).hasSize(1);
  assertThat(links.findByIncidentIdOrderByLinkedAtAscIdAsc(id)).hasSize(1);
  assertThat(outbox.count()).isEqualTo(1);
  String secondAlert=submitAlert();
  result(post(path(id)+"/alerts"),admin,Map.of("alertId",secondAlert),201);
  assertThat(links.findByIncidentIdOrderByLinkedAtAscIdAsc(id)).hasSize(2);
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
  var assignmentHistory=assignments.findByIncidentIdOrderByAssignedAtAscIdAsc(id);
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
  assertThat(history.findByIncidentIdOrderByChangedAtAscIdAsc(id)).hasSize(10);
  assertThat(assignments.findByIncidentIdOrderByAssignedAtAscIdAsc(id)).hasSize(3);
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
   .andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/teams/"+other.getId()+"/alerts").header("Authorization",bearer(viewer)))
   .andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/teams/"+team.getId()+"/members?size=2").header("Authorization",bearer(viewer)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
   .andExpect(jsonPath("$.totalElements").value(3));
  mvc.perform(get("/api/v1/teams/"+other.getId()+"/members").header("Authorization",bearer(viewer)))
   .andExpect(status().isNotFound());
  String foreignAlert=alerts.saveAndFlush(new Alert(other.getId(),otherService.getId(),"prometheus","foreign-alert",
   "Other team signal",null,Severity.SEV2,Instant.now(),"{}")).getId();
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
