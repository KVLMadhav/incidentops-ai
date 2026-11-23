package com.incidentops.identity.team;

import com.incidentops.entity.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_members")
public class TeamMember {
    @Id @Column(length = 36) private String id;
    @Column(name = "team_id", nullable = false, length = 36) private String teamId;
    @Column(name = "user_id", nullable = false, length = 36) private String userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role;
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant updatedAt;

    protected TeamMember() { }

    public TeamMember(String teamId, String userId, Role role) {
        this.id = UUID.randomUUID().toString();
        this.teamId = teamId;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getTeamId() { return teamId; }
    public String getUserId() { return userId; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
