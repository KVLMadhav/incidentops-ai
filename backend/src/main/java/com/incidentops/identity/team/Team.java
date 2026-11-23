package com.incidentops.identity.team;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "name_key", nullable = false, unique = true, length = 100) private String nameKey;
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant updatedAt;

    protected Team() { }

    public Team(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name.trim();
        this.nameKey = normalizeName(name);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static String normalizeName(String name) { return name.trim().toLowerCase(Locale.ROOT); }
    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
