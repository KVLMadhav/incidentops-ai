package com.incidentops.identity.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, String> {
    Optional<TeamMember> findByTeamIdAndUserId(String teamId, String userId);
    boolean existsByTeamIdAndUserId(String teamId, String userId);
    List<TeamMember> findAllByUserIdOrderByCreatedAtAscIdAsc(String userId);
    Page<TeamMember> findByTeamId(String teamId, Pageable pageable);
}
