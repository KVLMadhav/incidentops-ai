package com.incidentops.incident;
import com.fasterxml.jackson.databind.*;
import com.incidentops.catalog.MonitoredServiceRepository;
import com.incidentops.entity.Role;
import com.incidentops.exception.ApiException;
import com.incidentops.identity.team.TeamService;
import com.incidentops.identity.team.TeamMemberRepository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import jakarta.persistence.criteria.Predicate;
import static com.incidentops.incident.IncidentDtos.*;

@Service
public class IncidentService {
 private final Clock clock;
 private final TeamService teams;
 private final TeamMemberRepository members;
 private final MonitoredServiceRepository services;
 private final AlertRepository alerts;
 private final IncidentRepository incidents;
 private final IncidentAlertRepository links;
 private final IncidentAssignmentRepository assignments;
 private final IncidentNoteRepository notes;
 private final IncidentStatusHistoryRepository history;
 private final AuditLogRepository audits;
 private final OutboxEventRepository outbox;
 private final IncidentLifecycleService lifecycle;
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final SlaService sla;
 private final CommandReceipts receipts;
 private final ReliabilityCache cache;
 private final io.micrometer.core.instrument.MeterRegistry metrics;
 public IncidentService(TeamService teams,TeamMemberRepository members,MonitoredServiceRepository services,
   AlertRepository alerts,IncidentRepository incidents,IncidentAlertRepository links,
   IncidentAssignmentRepository assignments,IncidentNoteRepository notes,
   IncidentStatusHistoryRepository history,AuditLogRepository audits,OutboxEventRepository outbox,
   IncidentLifecycleService lifecycle,JdbcTemplate jdbc,ObjectMapper json,SlaService sla,CommandReceipts receipts,
   ReliabilityCache cache,io.micrometer.core.instrument.MeterRegistry metrics,Clock clock) {
  this.clock=clock;this.teams=teams;this.members=members;this.services=services;this.alerts=alerts;this.incidents=incidents;
  this.links=links;this.assignments=assignments;this.notes=notes;this.history=history;this.audits=audits;
  this.outbox=outbox;this.lifecycle=lifecycle;this.jdbc=jdbc;this.json=json;
  this.sla=sla;this.receipts=receipts;
  this.cache=cache;this.metrics=metrics;
 }
 @Transactional(noRollbackFor=AlertConflictException.class)
 public AlertResponse submitAlert(String actor,String team,CreateAlertRequest r){
  teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER,Role.RESPONDER);
  // Dedicated database lock stripes serialize same-team ingestion across instances.
  // Avoid a team-parent FOR UPDATE lock: incident FK writes could otherwise invert lock order.
  // The V4 unique constraint remains the final identity guard for all writers.
  jdbc.queryForList("SELECT lock_slot FROM alert_ingestion_locks WHERE lock_slot=? FOR UPDATE",Math.floorMod(team.hashCode(),256));
  String metadata=metadata(r.metadata());
  var existing=alerts.findByTeamIdAndSourceAndExternalAlertId(team,r.source().trim(),r.externalAlertId().trim());
  if(existing.isPresent()) {
   Alert a=existing.get();List<String> different=new ArrayList<>();
   if(!a.getServiceId().equals(r.serviceId()))different.add("serviceId");
   if(!a.getTitle().equals(r.title().trim()))different.add("title");
   if(!Objects.equals(a.getDescription(),r.description()))different.add("description");
   if(a.getSeverity()!=r.severity())different.add("severity");
   if(!a.getOccurredAt().equals(r.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))different.add("occurredAt");
   if(!alertResponse(a).metadata().equals(r.metadata()==null?json.createObjectNode():r.metadata()))different.add("metadata");
   if(different.isEmpty()){metrics.counter("incidentops.alert.duplicates").increment();return alertResponse(a);}
   jdbc.update("INSERT INTO alert_retry_conflicts(id,team_id,alert_id,actor_user_id,differing_fields,correlation_id,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
    UUID.randomUUID().toString(),team,a.getId(),actor,String.join(",",different),correlation());
   metrics.counter("incidentops.alert.conflicts").increment();
   org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("alertId",a.getId()).addKeyValue("fields",different).log("Alert retry rejected");
   throw new AlertConflictException();
  }
  service(team,r.serviceId());
  Alert a=new Alert(team,r.serviceId(),r.source(),r.externalAlertId(),r.title(),r.description(),r.severity(),
   r.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),metadata,clock.instant());
  String fingerprint=AlertGrouping.fingerprint(r.serviceId(),r.metadata());a.groupingFingerprint(fingerprint);
  alerts.saveAndFlush(a);
  if(fingerprint!=null) {
   var candidates=jdbc.queryForList("""
    SELECT DISTINCT i.id FROM incidents i JOIN incident_alerts l ON l.incident_id=i.id
    JOIN alerts a ON a.id=l.alert_id WHERE i.team_id=? AND i.service_id=? AND a.grouping_fingerprint=?
    AND i.status NOT IN ('RESOLVED','CLOSED') ORDER BY i.id
    """,String.class,team,r.serviceId(),fingerprint);
   // Ambiguity requires a human choice; never merge incidents or discard the new alert.
   if(candidates.size()==1) {
    Incident i=commandIncident(actor,team,candidates.getFirst());
    if(i.getStatus()!=IncidentStatus.RESOLVED&&i.getStatus()!=IncidentStatus.CLOSED) {
     links.saveAndFlush(new IncidentAlert(team,i.getId(),actor,a.getId(),clock.instant()));
     audits.save(new AuditLog(team,i.getId(),actor,"ALERT_GROUPED",a.getId(),clock.instant()));event(i,"INCIDENT_ALERT_LINKED");
    }
   }
  }
  return alertResponse(a);
 }
 @Transactional(readOnly=true)
 public PageResponse<AlertResponse> listAlerts(String actor,String team,Severity severity,String service,Instant from,Instant to,int page,int size){
  teams.requireMembership(actor,team);range(from,to);
  Specification<Alert> filters=(root,query,cb)->{
   List<Predicate> values=new ArrayList<>();values.add(cb.equal(root.get("teamId"),team));
   if(severity!=null)values.add(cb.equal(root.get("severity"),severity));
   if(service!=null&&!service.isBlank())values.add(cb.equal(root.get("serviceId"),service));
   if(from!=null)values.add(cb.greaterThanOrEqualTo(root.get("receivedAt"),from));
   if(to!=null)values.add(cb.lessThanOrEqualTo(root.get("receivedAt"),to));
   return cb.and(values.toArray(Predicate[]::new));
  };
  var result=alerts.findAll(filters,PageRequest.of(page,size,Sort.by(Sort.Order.desc("receivedAt"),Sort.Order.asc("id"))));
  return new PageResponse<>(result.map(this::alertResponse).getContent(),page,size,result.getTotalElements(),result.getTotalPages());
 }
 @Transactional
 public IncidentResponse create(String actor,String team,CreateIncidentRequest r){
  teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER,Role.RESPONDER);
  service(team,r.serviceId());
  List<String> ids=r.alertIds()==null?List.of():r.alertIds();
  if(ids.size()>50 || ids.size()!=new HashSet<>(ids).size())
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ALERT_LINKS","Use at most 50 distinct alert IDs");
  for(String id:ids) alert(team,id,r.serviceId());
  Long sequence=jdbc.queryForObject("select nextval('incident_number_seq')",Long.class);
  String number="INC-"+Year.now(clock).getValue()+"-"+String.format("%06d",sequence);
  Incident incident=incidents.saveAndFlush(new Incident(team,r.serviceId(),number,r.title(),r.description(),r.severity(),actor,clock.instant()));
  sla.create(incident);
  history.saveAndFlush(new IncidentStatusHistory(team,incident.getId(),actor,null,IncidentStatus.OPEN,"Created",clock.instant()));
  audits.saveAndFlush(new AuditLog(team,incident.getId(),actor,"CREATED",number,clock.instant()));
  for(String id:ids) links.saveAndFlush(new IncidentAlert(team,incident.getId(),actor,id,clock.instant()));
  outbox.saveAndFlush(new OutboxEvent(team,incident.getId(),"INCIDENT_CREATED",
   "{\"incidentId\":\""+incident.getId()+"\",\"incidentNumber\":\""+number+"\"}",clock.instant(),correlation()));
  cache.invalidateAfterCommit(team,incident.getId());
  return response(incident);
 }
 @Transactional(readOnly=true)
 public PageResponse<IncidentResponse> list(String actor,String team,Severity severity,IncidentStatus status,
  String service,String assignee,Instant from,Instant to,int page,int size){
  teams.requireMembership(actor,team);range(from,to);
  Specification<Incident> filters=(root,query,cb)->{
   List<Predicate> values=new ArrayList<>();values.add(cb.equal(root.get("teamId"),team));
   if(severity!=null)values.add(cb.equal(root.get("severity"),severity));
   if(status!=null)values.add(cb.equal(root.get("status"),status));
   if(service!=null&&!service.isBlank())values.add(cb.equal(root.get("serviceId"),service));
   if(assignee!=null&&!assignee.isBlank())values.add(cb.equal(root.get("assigneeUserId"),assignee));
   if(from!=null)values.add(cb.greaterThanOrEqualTo(root.get("createdAt"),from));
   if(to!=null)values.add(cb.lessThanOrEqualTo(root.get("createdAt"),to));
   return cb.and(values.toArray(Predicate[]::new));
  };
  var result=incidents.findAll(filters,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.asc("id"))));
  return new PageResponse<>(result.map(this::response).getContent(),page,size,result.getTotalElements(),result.getTotalPages());
 }
 @Transactional(readOnly=true)
 public IncidentResponse read(String actor,String team,String id){
  teams.requireMembership(actor,team);
  String key=ReliabilityCache.detailKey(team,id);
  var cached=cache.get(key,IncidentResponse.class);if(cached!=null)return cached;
  var result=response(incident(actor,team,id));cache.putAfterCommit(key,result);return result;
 }
 @Transactional
 public IncidentResponse details(String actor,String team,String id,DetailsRequest r){
  Incident i=incident(actor,team,id);teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER);
  version(i,r.version());i.details(r.title(),r.description(),r.severity(),clock.instant());
  audits.save(new AuditLog(team,id,actor,"DETAILS_UPDATED",null,clock.instant()));
  event(i,"INCIDENT_UPDATED");
  return response(incidents.saveAndFlush(i));
 }
 @Transactional
 public IncidentResponse assign(String actor,String team,String id,AssignRequest r){
  Incident i=commandIncident(actor,team,id);teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER);
  IncidentResponse replay=receipts.replay(id,r.commandId(),actor,"ASSIGN",r,IncidentResponse.class);
  if(replay!=null)return replay;
  version(i,r.version());
  var assignee=members.findByTeamIdAndUserId(team,r.assigneeUserId()).orElseThrow(()->
   new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ASSIGNEE","Assignee must belong to the same team"));
  if(assignee.getRole()==Role.VIEWER)
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ASSIGNEE","A viewer cannot be assigned as a responder");
  String previous=i.getAssigneeUserId();
  i.assignee(r.assigneeUserId(),clock.instant());
  assignments.save(new IncidentAssignment(team,id,actor,r.assigneeUserId(),previous,r.reason(),clock.instant()));
  audits.save(new AuditLog(team,id,actor,"ASSIGNED",r.assigneeUserId(),clock.instant()));
  event(i,"INCIDENT_ASSIGNED");
  return receipts.store(id,r.commandId(),actor,"ASSIGN",r,response(incidents.saveAndFlush(i)));
 }
 @Transactional
 public IncidentResponse transition(String actor,String team,String id,TransitionRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  IncidentResponse replay=receipts.replay(id,r.commandId(),actor,"TRANSITION",r,IncidentResponse.class);
  if(replay!=null)return replay;
  version(i,r.version());
  if(r.status()==IncidentStatus.RESOLVED&&(r.reason()==null||r.reason().isBlank()))
   throw new ApiException(HttpStatus.BAD_REQUEST,"RESOLUTION_REQUIRED","Explain how the incident was resolved in reason");
  IncidentStatusHistory entry=lifecycle.transition(i,r.status(),actor,r.reason());
  sla.complete(id,r.status());
  history.save(entry);
  audits.save(new AuditLog(team,id,actor,"STATUS_CHANGED",entry.getOldStatus()+" -> "+entry.getNewStatus(),clock.instant()));
  event(i,"INCIDENT_STATUS_CHANGED");
  return receipts.store(id,r.commandId(),actor,"TRANSITION",r,response(incidents.saveAndFlush(i)));
 }
 @Transactional
 public AlertResponse link(String actor,String team,String id,LinkAlertRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  Alert a=alert(team,r.alertId(),i.getServiceId());
  if(links.existsByTeamIdAndIncidentIdAndAlertId(team,id,a.getId()))
   throw new ApiException(HttpStatus.CONFLICT,"ALERT_ALREADY_LINKED","Alert is already linked to this incident");
  links.saveAndFlush(new IncidentAlert(team,id,actor,a.getId(),clock.instant()));
  audits.save(new AuditLog(team,id,actor,"ALERT_LINKED",a.getId(),clock.instant()));
  event(i,"INCIDENT_ALERT_LINKED");
  return alertResponse(a);
 }
 @Transactional
 public TimelineEntry note(String actor,String team,String id,NoteRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  TimelineEntry replay=receipts.replay(id,r.commandId(),actor,"NOTE",r,TimelineEntry.class);
  if(replay!=null)return replay;
  IncidentNote n=notes.saveAndFlush(new IncidentNote(team,id,actor,r.body(),clock.instant()));
  audits.save(new AuditLog(team,id,actor,"NOTE_ADDED",n.getId(),clock.instant()));
  event(i,"INCIDENT_NOTE_ADDED");
  return receipts.store(id,r.commandId(),actor,"NOTE",r,new TimelineEntry(n.getId(),"NOTE",n.getCreatedAt(),actor,n.getBody()));
 }
 @Transactional(readOnly=true)
 public List<TimelineEntry> timeline(String actor,String team,String id){
  incident(actor,team,id);
  List<TimelineEntry> all=new ArrayList<>();
  history.findByTeamIdAndIncidentIdOrderByChangedAtAscIdAsc(team,id).forEach(e->all.add(new TimelineEntry(e.getId(),"STATUS",e.getChangedAt(),e.getActorUserId(),
   (e.getOldStatus()==null?"START":e.getOldStatus())+" -> "+e.getNewStatus()+(e.getReason()==null?"":" | "+e.getReason()))));
  assignments.findByTeamIdAndIncidentIdOrderByAssignedAtAscIdAsc(team,id).forEach(e->all.add(new TimelineEntry(e.getId(),"ASSIGNMENT",e.getAssignedAt(),e.getActorUserId(),
   e.getAssigneeUserId()+(e.getReason()==null?"":" | "+e.getReason()))));
  notes.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team,id).forEach(e->all.add(new TimelineEntry(e.getId(),"NOTE",e.getCreatedAt(),e.getActorUserId(),e.getBody())));
  links.findByTeamIdAndIncidentIdOrderByLinkedAtAscIdAsc(team,id).forEach(e->all.add(new TimelineEntry(e.getId(),"ALERT_LINK",e.getLinkedAt(),e.getActorUserId(),e.getAlertId())));
  jdbc.query("""
   SELECT e.id, e.type, e.created_at, e.detail, e.correlation_id
   FROM incident_operational_timeline e JOIN incidents i ON i.id=e.incident_id
   WHERE i.team_id=? AND i.id=?
   """,(org.springframework.jdbc.core.RowCallbackHandler)rs->
   all.add(new TimelineEntry(rs.getString("id"),rs.getString("type"),rs.getTimestamp("created_at").toInstant(),null,
    rs.getString("detail")+" | correlation="+rs.getString("correlation_id"))),team,id);
  all.sort(Comparator.comparing(TimelineEntry::at)
   .thenComparingInt(e->"STATUS".equals(e.type())?0:1).thenComparing(TimelineEntry::id));
  return all;
 }
 @Transactional(readOnly=true)
 public List<AuditLog> audit(String actor,String team,String id){
  incident(actor,team,id);return audits.findByTeamIdAndIncidentIdOrderByCreatedAtAscIdAsc(team,id);
 }
 private Incident incident(String actor,String team,String id){
  teams.requireMembership(actor,team);
  return incidents.findByIdAndTeamId(id,team).orElseThrow(()->
   new ApiException(HttpStatus.NOT_FOUND,"INCIDENT_NOT_FOUND","Incident was not found"));
 }
 @Transactional(readOnly=true)
 public List<SlaService.Target> sla(String actor,String team,String id){
  incident(actor,team,id);return sla.targets(id);
 }
 private Incident commandIncident(String actor,String team,String id){
  teams.requireMembership(actor,team);
  return incidents.findForCommand(id,team).orElseThrow(()->
   new ApiException(HttpStatus.NOT_FOUND,"INCIDENT_NOT_FOUND","Incident was not found"));
 }
 private void requireResponder(String actor,String team,Incident i){
  var member=teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER,Role.RESPONDER);
  if(member.getRole()==Role.RESPONDER&&!actor.equals(i.getAssigneeUserId()))
   throw new ApiException(HttpStatus.FORBIDDEN,"NOT_ASSIGNED","Only the assigned responder or a team manager can change this incident");
 }
 private void event(Incident i,String type){
  var event=outbox.saveAndFlush(new OutboxEvent(i.getTeamId(),i.getId(),type,"{}",clock.instant(),correlation()));
  String target="INCIDENT_ASSIGNED".equals(type)?"USER:"+i.getAssigneeUserId():"TEAM";
  String message="INCIDENT_STATUS_CHANGED".equals(type)?"Incident "+i.getStatus():type.replace('_',' ');
  jdbc.update("UPDATE outbox_events SET notification_target=?,notification_message=? WHERE id=?",target,message,event.getId());
  cache.invalidateAfterCommit(i.getTeamId(),i.getId());
 }
 private static String correlation(){String value=org.slf4j.MDC.get("correlationId");return value==null?UUID.randomUUID().toString():value;}
 private Alert alert(String team,String id,String service){
  return alerts.findByIdAndTeamIdAndServiceId(id,team,service).orElseThrow(()->
   new ApiException(HttpStatus.NOT_FOUND,"ALERT_NOT_FOUND","Alert was not found for this team and service"));
 }
 private void service(String team,String id){
  if(services.findByIdAndTeamId(id,team).filter(s->s.isActive()).isEmpty())
   throw new ApiException(HttpStatus.NOT_FOUND,"SERVICE_NOT_FOUND","Active monitored service was not found");
 }
 private static void version(Incident i,Long expected){
  if(expected==null||expected!=i.getVersion())
   throw new ApiException(HttpStatus.CONFLICT,"STALE_VERSION","Another person changed this incident. Reload it and try again.");
 }
 private static void range(Instant from,Instant to){
  if(from!=null&&to!=null&&from.isAfter(to))
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TIME_RANGE","from must be before or equal to to");
 }
 private String metadata(JsonNode node){
  if(node==null)return "{}";
  if(!node.isObject())throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_METADATA","Metadata must be a JSON object");
  String value=node.toString();
  if(value.length()>4000)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_METADATA","Metadata exceeds 4000 characters");
  return value;
 }
 private AlertResponse alertResponse(Alert a){
  try{return new AlertResponse(a.getId(),a.getTeamId(),a.getServiceId(),a.getSource(),a.getExternalAlertId(),
   a.getTitle(),a.getDescription(),a.getSeverity(),a.getOccurredAt(),a.getReceivedAt(),json.readTree(a.getMetadata()));}
  catch(Exception e){throw new IllegalStateException("Stored alert metadata is invalid",e);}
 }
 private IncidentResponse response(Incident i){
  return new IncidentResponse(i.getId(),i.getTeamId(),i.getServiceId(),i.getIncidentNumber(),i.getTitle(),
   i.getDescription(),i.getSeverity(),i.getStatus(),i.getVersion(),i.getCreatorUserId(),i.getAssigneeUserId(),
   i.getCreatedAt(),i.getUpdatedAt());
 }
}
