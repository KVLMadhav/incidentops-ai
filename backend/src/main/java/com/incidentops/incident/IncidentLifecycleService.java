package com.incidentops.incident;
import com.incidentops.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class IncidentLifecycleService {
 private static final Map<IncidentStatus,List<IncidentStatus>> NEXT=Map.of(
  IncidentStatus.OPEN,List.of(IncidentStatus.ACKNOWLEDGED),
  IncidentStatus.ACKNOWLEDGED,List.of(IncidentStatus.INVESTIGATING),
  IncidentStatus.INVESTIGATING,List.of(IncidentStatus.MITIGATED),
  IncidentStatus.MITIGATED,List.of(IncidentStatus.RESOLVED),
  IncidentStatus.RESOLVED,List.of(IncidentStatus.CLOSED,IncidentStatus.REOPENED),
  IncidentStatus.REOPENED,List.of(IncidentStatus.INVESTIGATING),
  IncidentStatus.CLOSED,List.of());
 public List<IncidentStatus> allowed(IncidentStatus status){return NEXT.get(status);}
 public IncidentStatusHistory transition(Incident incident,IncidentStatus next,String actor,String reason){
  IncidentStatus old=incident.getStatus();
  if(!NEXT.get(old).contains(next))
   throw new ApiException(HttpStatus.CONFLICT,"INVALID_TRANSITION",
    "Cannot move from "+old+" to "+next+". Allowed next statuses: "+NEXT.get(old));
  incident.status(next);
  return new IncidentStatusHistory(incident.getTeamId(),incident.getId(),actor,old,next,reason);
 }
}
