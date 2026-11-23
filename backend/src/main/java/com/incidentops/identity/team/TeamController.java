package com.incidentops.identity.team;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.incidentops.identity.team.TeamDtos.*;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService service;
    public TeamController(TeamService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TeamResponse> create(Authentication authentication,
                                                @Valid @RequestBody CreateTeamRequest request) {
        return ResponseEntity.status(201).body(service.create(authentication.getName(), request));
    }

    @GetMapping
    public TeamList list(Authentication authentication) {
        return service.list(authentication.getName());
    }

    @PostMapping("/{teamId}/members")
    public ResponseEntity<MemberResponse> addMember(Authentication authentication,
        @PathVariable String teamId, @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(201).body(service.addMember(authentication.getName(), teamId, request));
    }

    @GetMapping("/{teamId}/members")
    public MemberPage members(Authentication authentication, @PathVariable String teamId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listMembers(authentication.getName(), teamId, page, size);
    }
}
