package com.incidentops.identity.team;

import com.incidentops.entity.Role;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class TeamDtos {
    private TeamDtos() { }

    public record CreateTeamRequest(@NotBlank @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s)\\S(?:.*\\S)", message = "must contain at least two characters and no surrounding whitespace") String name) { }
    public record AddMemberRequest(@NotBlank @Size(max = 36) String userId, @NotNull Role role) { }
    public record TeamResponse(String id, String name, Role role, Instant createdAt, Instant updatedAt) { }
    public record MemberResponse(String id, String teamId, String userId, Role role,
                                 Instant createdAt, Instant updatedAt) { }
    public record MemberOption(String userId, String displayName, Role role) { }
    public record MemberPage(List<MemberOption> items, int page, int size, long totalElements, int totalPages) { }
    public record TeamList(List<TeamResponse> items) { }
}
