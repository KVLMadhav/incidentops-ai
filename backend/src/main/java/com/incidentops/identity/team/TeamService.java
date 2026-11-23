package com.incidentops.identity.team;

import com.incidentops.entity.Role;
import com.incidentops.exception.ApiException;
import com.incidentops.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import static com.incidentops.identity.team.TeamDtos.*;

@Service
public class TeamService {
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserRepository users;

    public TeamService(TeamRepository teams, TeamMemberRepository members, UserRepository users) {
        this.teams = teams;
        this.members = members;
        this.users = users;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TeamResponse create(String currentUserId, CreateTeamRequest request) {
        var team = teams.saveAndFlush(new Team(request.name()));
        members.saveAndFlush(new TeamMember(team.getId(), currentUserId, Role.ADMIN));
        return response(team, Role.ADMIN);
    }

    @Transactional(readOnly = true)
    public TeamList list(String currentUserId) {
        var items = members.findAllByUserIdOrderByCreatedAtAscIdAsc(currentUserId).stream()
            .map(member -> teams.findById(member.getTeamId())
                .map(team -> response(team, member.getRole())).orElse(null))
            .filter(java.util.Objects::nonNull).toList();
        return new TeamList(items);
    }

    @Transactional
    public MemberResponse addMember(String currentUserId, String teamId, AddMemberRequest request) {
        requireRole(currentUserId, teamId, Role.ADMIN);
        if (!users.existsById(request.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OWNER", "The selected user does not exist");
        }
        var member = members.saveAndFlush(new TeamMember(teamId, request.userId(), request.role()));
        return new MemberResponse(member.getId(), member.getTeamId(), member.getUserId(), member.getRole(),
            member.getCreatedAt(), member.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public MemberPage listMembers(String currentUserId, String teamId, int page, int size) {
        requireMembership(currentUserId, teamId);
        var result = members.findByTeamId(teamId, PageRequest.of(page, size,
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        var items = result.getContent().stream().map(member -> new MemberOption(member.getUserId(),
            users.findById(member.getUserId()).map(user -> user.getDisplayName()).orElse("Unknown user"),
            member.getRole())).toList();
        return new MemberPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    public TeamMember requireMembership(String userId, String teamId) {
        return members.findByTeamIdAndUserId(teamId, userId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team was not found"));
    }

    public TeamMember requireRole(String userId, String teamId, Role... allowed) {
        var member = requireMembership(userId, teamId);
        if (java.util.Arrays.stream(allowed).noneMatch(role -> role == member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient team permissions");
        }
        return member;
    }

    private static TeamResponse response(Team team, Role role) {
        return new TeamResponse(team.getId(), team.getName(), role, team.getCreatedAt(), team.getUpdatedAt());
    }
}
