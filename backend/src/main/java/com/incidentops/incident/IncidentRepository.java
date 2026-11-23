package com.incidentops.incident;
import org.springframework.data.jpa.repository.*;
public interface IncidentRepository extends JpaRepository<Incident,String>, JpaSpecificationExecutor<Incident> {
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select i from Incident i where i.id=:id and i.teamId=:team")
 java.util.Optional<Incident> findForCommand(@org.springframework.data.repository.query.Param("id") String id,
   @org.springframework.data.repository.query.Param("team") String team);
 java.util.Optional<Incident> findByIdAndTeamId(String id,String teamId);
}
