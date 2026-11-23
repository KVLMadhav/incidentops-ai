package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface IncidentStatusHistoryRepository extends JpaRepository<IncidentStatusHistory,String> {
 List<IncidentStatusHistory> findByIncidentIdOrderByChangedAtAscIdAsc(String incidentId);
}
