package com.incidentops.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "monitored_services")
public class MonitoredService {
    @Id @Column(length = 36) private String id;
    @Column(name = "team_id", nullable = false, length = 36) private String teamId;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "name_key", nullable = false, length = 100) private String nameKey;
    @Column(length = 2000) private String description;
    @Column(name = "owner_user_id", length = 36) private String ownerUserId;
    @Column(name = "external_identifier", length = 100) private String externalIdentifier;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone") private Instant updatedAt;

    protected MonitoredService() { }

    public MonitoredService(String teamId, String name, String description, String ownerUserId,
                            String externalIdentifier) {
        this.id = UUID.randomUUID().toString();
        this.teamId = teamId;
        apply(name, description, ownerUserId, externalIdentifier);
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String name, String description, String ownerUserId, String externalIdentifier) {
        apply(name, description, ownerUserId, externalIdentifier);
        this.updatedAt = Instant.now();
    }

    public void deactivate() { this.active = false; this.updatedAt = Instant.now(); }

    private void apply(String name, String description, String ownerUserId, String externalIdentifier) {
        this.name = name.trim();
        this.nameKey = name.trim().toLowerCase(Locale.ROOT);
        this.description = blankToNull(description);
        this.ownerUserId = blankToNull(ownerUserId);
        this.externalIdentifier = blankToNull(externalIdentifier);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getId() { return id; }
    public String getTeamId() { return teamId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getExternalIdentifier() { return externalIdentifier; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
