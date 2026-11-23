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
 public IncidentService(TeamService teams,TeamMemberRepository members,MonitoredServiceRepository services,
   AlertRepository alerts,IncidentRepository incidents,IncidentAlertRepository links,
   IncidentAssignmentRepository assignments,IncidentNoteRepository notes,
   IncidentStatusHistoryRepository history,AuditLogRepository audits,OutboxEventRepository outbox,
   IncidentLifecycleService lifecycle,JdbcTemplate jdbc,ObjectMapper json) {
  this.teams=teams;this.members=members;this.services=services;this.alerts=alerts;this.incidents=incidents;
  this.links=links;this.assignments=assignments;this.notes=notes;this.history=history;this.audits=audits;
  this.outbox=outbox;this.lifecycle=lifecycle;this.jdbc=jdbc;this.json=json;
 }
 @Transactional
 public AlertResponse submitAlert(String actor,String team,CreateAlertRequest r){
  teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER,Role.RESPONDER);
  service(team,r.serviceId());
  String metadata=metadata(r.metadata());
  return alertResponse(alerts.saveAndFlush(new Alert(team,r.serviceId(),r.source(),r.externalAlertId(),
   r.title(),r.description(),r.severity(),r.occurredAt(),metadata)));
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
  String number="INC-"+Year.now(ZoneOffset.UTC).getValue()+"-"+String.format("%06d",sequence);
  Incident incident=incidents.saveAndFlush(new Incident(team,r.serviceId(),number,r.title(),r.description(),r.severity(),actor));
  history.saveAndFlush(new IncidentStatusHistory(team,incident.getId(),actor,null,IncidentStatus.OPEN,"Created"));
  audits.saveAndFlush(new AuditLog(team,incident.getId(),actor,"CREATED",number));
  for(String id:ids) links.saveAndFlush(new IncidentAlert(team,incident.getId(),actor,id));
  outbox.saveAndFlush(new OutboxEvent(team,incident.getId(),"INCIDENT_CREATED",
   "{\"incidentId\":\""+incident.getId()+"\",\"incidentNumber\":\""+number+"\"}"));
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
 public IncidentResponse read(String actor,String team,String id){return response(incident(actor,team,id));}
 @Transactional
 public IncidentResponse details(String actor,String team,String id,DetailsRequest r){
  Incident i=incident(actor,team,id);teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER);
  version(i,r.version());i.details(r.title(),r.description(),r.severity());
  audits.save(new AuditLog(team,id,actor,"DETAILS_UPDATED",null));
  event(i,"INCIDENT_UPDATED");
  return response(incidents.saveAndFlush(i));
 }
 @Transactional
 public IncidentResponse assign(String actor,String team,String id,AssignRequest r){
  Incident i=commandIncident(actor,team,id);teams.requireRole(actor,team,Role.ADMIN,Role.INCIDENT_MANAGER);
  version(i,r.version());
  var assignee=members.findByTeamIdAndUserId(team,r.assigneeUserId()).orElseThrow(()->
   new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ASSIGNEE","Assignee must belong to the same team"));
  if(assignee.getRole()==Role.VIEWER)
   throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ASSIGNEE","A viewer cannot be assigned as a responder");
  String previous=i.getAssigneeUserId();
  i.assignee(r.assigneeUserId());
  assignments.save(new IncidentAssignment(team,id,actor,r.assigneeUserId(),previous,r.reason()));
  audits.save(new AuditLog(team,id,actor,"ASSIGNED",r.assigneeUserId()));
  event(i,"INCIDENT_ASSIGNED");
  return response(incidents.saveAndFlush(i));
 }
 @Transactional
 public IncidentResponse transition(String actor,String team,String id,TransitionRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  version(i,r.version());
  IncidentStatusHistory entry=lifecycle.transition(i,r.status(),actor,r.reason());
  history.save(entry);
  audits.save(new AuditLog(team,id,actor,"STATUS_CHANGED",entry.getOldStatus()+" -> "+entry.getNewStatus()));
  event(i,"INCIDENT_STATUS_CHANGED");
  return response(incidents.saveAndFlush(i));
 }
 @Transactional
 public AlertResponse link(String actor,String team,String id,LinkAlertRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  Alert a=alert(team,r.alertId(),i.getServiceId());
  if(links.existsByIncidentIdAndAlertId(id,a.getId()))
   throw new ApiException(HttpStatus.CONFLICT,"ALERT_ALREADY_LINKED","Alert is already linked to this incident");
  links.saveAndFlush(new IncidentAlert(team,id,actor,a.getId()));
  audits.save(new AuditLog(team,id,actor,"ALERT_LINKED",a.getId()));
  event(i,"INCIDENT_ALERT_LINKED");
  return alertResponse(a);
 }
 @Transactional
 public TimelineEntry note(String actor,String team,String id,NoteRequest r){
  Incident i=commandIncident(actor,team,id);requireResponder(actor,team,i);
  IncidentNote n=notes.saveAndFlush(new IncidentNote(team,id,actor,r.body()));
  audits.save(new AuditLog(team,id,actor,"NOTE_ADDED",n.getId()));
  event(i,"INCIDENT_NOTE_ADDED");
  return new TimelineEntry(n.getId(),"NOTE",n.getCreatedAt(),actor,n.getBody());
 }
 @Transactional(readOnly=true)
 public List<TimelineEntry> timeline(String actor,String team,String id){
  incident(actor,team,id);
  List<TimelineEntry> all=new ArrayList<>();
  history.findByIncidentIdOrderByChangedAtAscIdAsc(id).forEach(e->all.add(new TimelineEntry(e.getId(),"STATUS",e.getChangedAt(),e.getActorUserId(),
   (e.getOldStatus()==null?"START":e.getOldStatus())+" -> "+e.getNewStatus()+(e.getReason()==null?"":" | "+e.getReason()))));
  assignments.findByIncidentIdOrderByAssignedAtAscIdAsc(id).forEach(e->all.add(new TimelineEntry(e.getId(),"ASSIGNMENT",e.getAssignedAt(),e.getActorUserId(),
   e.getAssigneeUserId()+(e.getReason()==null?"":" | "+e.getReason()))));
  notes.findByIncidentIdOrderByCreatedAtAscIdAsc(id).forEach(e->all.add(new TimelineEntry(e.getId(),"NOTE",e.getCreatedAt(),e.getActorUserId(),e.getBody())));
  links.findByIncidentIdOrderByLinkedAtAscIdAsc(id).forEach(e->all.add(new TimelineEntry(e.getId(),"ALERT_LINK",e.getLinkedAt(),e.getActorUserId(),e.getAlertId())));
  all.sort(Comparator.comparing(TimelineEntry::at)
   .thenComparingInt(e->"STATUS".equals(e.type())?0:1).thenComparing(TimelineEntry::id));
  return all;
 }
 @Transactional(readOnly=true)
 public List<AuditResponse> audit(String actor,String team,String id){
  incident(actor,team,id);return audits.findByIncidentIdOrderByCreatedAtAscIdAsc(id).stream().map(e->new AuditResponse(e.getId(),e.getTeamId(),e.getIncidentId(),e.getActorUserId(),e.getAction(),e.getDetail(),e.getCreatedAt())).toList();
 }
 private Incident incident(String actor,String team,String id){
  teams.requireMembership(actor,team);
  return incidents.findByIdAndTeamId(id,team).orElseThrow(()->
   new ApiException(HttpStatus.NOT_FOUND,"INCIDENT_NOT_FOUND","Incident was not found"));
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
 private void event(Incident i,String type){outbox.save(new OutboxEvent(i.getTeamId(),i.getId(),type,"{}"));}
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
