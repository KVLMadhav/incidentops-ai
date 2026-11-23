package com.incidentops.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** One login creates one family of rotating refresh tokens. */
@Entity @Table(name="auth_sessions")
public class AuthSession {
    @Id @Column(length=36) private String id;
    @Column(name="user_id", nullable=false, length=36) private String userId;
    @Column(name="expires_at", nullable=false, columnDefinition="timestamp with time zone")
    private Instant expiresAt;
    @Column(nullable=false) private boolean revoked;
    @Column(name="created_at", nullable=false, columnDefinition="timestamp with time zone")
    private Instant createdAt;
    protected AuthSession() { }
    public AuthSession(String userId, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive(Instant now) { return !revoked && expiresAt.isAfter(now); }
    public void revoke() { revoked = true; }
}
