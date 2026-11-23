package com.incidentops.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, String> {
    Page<MonitoredService> findAllByTeamId(String teamId, Pageable pageable);
    Optional<MonitoredService> findByIdAndTeamId(String id, String teamId);

    @Query("select service from MonitoredService service where service.id = :serviceId and exists " +
        "(select member.id from TeamMember member where member.teamId = service.teamId and member.userId = :userId)")
    Optional<MonitoredService> findPermittedById(@Param("serviceId") String serviceId,
                                                  @Param("userId") String userId);
}
