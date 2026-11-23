package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface IncidentAssignmentRepository extends JpaRepository<IncidentAssignment,String> {
 List<IncidentAssignment> findByIncidentIdOrderByAssignedAtAscIdAsc(String incidentId);
}
