package com.incidentops.incident;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import static com.incidentops.incident.IncidentDtos.*;

@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class IncidentController {
 private final IncidentService service;
 public IncidentController(IncidentService service){this.service=service;}
 @PostMapping("/alerts")
 public ResponseEntity<AlertResponse> alert(Authentication auth,@PathVariable String teamId,@Valid @RequestBody CreateAlertRequest request){
  return ResponseEntity.status(201).body(service.submitAlert(auth.getName(),teamId,request));
 }
 @GetMapping("/alerts")
 public PageResponse<AlertResponse> alerts(Authentication auth,@PathVariable String teamId,
  @RequestParam(required=false) Severity severity,@RequestParam(required=false) String serviceId,
  @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
  @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
  @RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
  return service.listAlerts(auth.getName(),teamId,severity,serviceId,from,to,page,size);
 }
 @PostMapping("/incidents")
 public ResponseEntity<IncidentResponse> create(Authentication auth,@PathVariable String teamId,@Valid @RequestBody CreateIncidentRequest request){
  return ResponseEntity.status(201).body(service.create(auth.getName(),teamId,request));
 }
 @GetMapping("/incidents")
 public PageResponse<IncidentResponse> incidents(Authentication auth,@PathVariable String teamId,
  @RequestParam(required=false) Severity severity,@RequestParam(required=false) IncidentStatus status,
  @RequestParam(required=false) String serviceId,@RequestParam(required=false) String assigneeUserId,
  @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
  @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
  @RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
  return service.list(auth.getName(),teamId,severity,status,serviceId,assigneeUserId,from,to,page,size);
 }
 @GetMapping("/incidents/{id}")
 public IncidentResponse read(Authentication auth,@PathVariable String teamId,@PathVariable String id){
  return service.read(auth.getName(),teamId,id);
 }
 @PutMapping("/incidents/{id}/details")
 public IncidentResponse details(Authentication auth,@PathVariable String teamId,@PathVariable String id,@Valid @RequestBody DetailsRequest request){
  return service.details(auth.getName(),teamId,id,request);
 }
 @PostMapping("/incidents/{id}/assignment")
 public IncidentResponse assign(Authentication auth,@PathVariable String teamId,@PathVariable String id,@Valid @RequestBody AssignRequest request){
  return service.assign(auth.getName(),teamId,id,request);
 }
 @PostMapping("/incidents/{id}/transitions")
 public IncidentResponse transition(Authentication auth,@PathVariable String teamId,@PathVariable String id,@Valid @RequestBody TransitionRequest request){
  return service.transition(auth.getName(),teamId,id,request);
 }
 @PostMapping("/incidents/{id}/alerts")
 public ResponseEntity<AlertResponse> link(Authentication auth,@PathVariable String teamId,@PathVariable String id,@Valid @RequestBody LinkAlertRequest request){
  return ResponseEntity.status(201).body(service.link(auth.getName(),teamId,id,request));
 }
 @PostMapping("/incidents/{id}/notes")
 public ResponseEntity<TimelineEntry> note(Authentication auth,@PathVariable String teamId,@PathVariable String id,@Valid @RequestBody NoteRequest request){
  return ResponseEntity.status(201).body(service.note(auth.getName(),teamId,id,request));
 }
 @GetMapping("/incidents/{id}/timeline")
 public List<TimelineEntry> timeline(Authentication auth,@PathVariable String teamId,@PathVariable String id){
  return service.timeline(auth.getName(),teamId,id);
 }
 @GetMapping("/incidents/{id}/audit")
 public List<AuditResponse> audit(Authentication auth,@PathVariable String teamId,@PathVariable String id){
  return service.audit(auth.getName(),teamId,id);
 }
}
