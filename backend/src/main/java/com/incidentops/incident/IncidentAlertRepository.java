package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface IncidentAlertRepository extends JpaRepository<IncidentAlert,String> {
 List<IncidentAlert> findByTeamIdAndIncidentIdOrderByLinkedAtAscIdAsc(String teamId, String incidentId);
 boolean existsByTeamIdAndIncidentIdAndAlertId(String teamId, String incidentId, String alertId);
}
