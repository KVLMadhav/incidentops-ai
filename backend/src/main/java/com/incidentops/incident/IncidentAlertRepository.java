package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface IncidentAlertRepository extends JpaRepository<IncidentAlert,String> {
 List<IncidentAlert> findByIncidentIdOrderByLinkedAtAscIdAsc(String incidentId);
 boolean existsByIncidentIdAndAlertId(String incidentId,String alertId);
}
