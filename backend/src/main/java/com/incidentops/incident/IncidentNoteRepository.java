package com.incidentops.incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface IncidentNoteRepository extends JpaRepository<IncidentNote,String> {
 List<IncidentNote> findByIncidentIdOrderByCreatedAtAscIdAsc(String incidentId);
}
