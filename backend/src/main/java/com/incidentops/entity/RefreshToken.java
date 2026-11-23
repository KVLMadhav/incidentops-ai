package com.incidentops.entity;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="refresh_tokens")
public class RefreshToken {
    @Id @Column(name="token_hash", length=64) private String tokenHash;
    @Column(name="session_id", nullable=false, length=36) private String sessionId;
    @Column(nullable=false) private boolean consumed;
    @Column(name="created_at", nullable=false, columnDefinition="timestamp with time zone")
    private Instant createdAt;
    @Column(name="consumed_at", columnDefinition="timestamp with time zone")
    private Instant consumedAt;
    protected RefreshToken() { }
    public RefreshToken(String tokenHash, String sessionId) {
        this.tokenHash = tokenHash;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
    }
    public boolean isConsumed() { return consumed; }
    public void consume() { consumed = true; consumedAt = Instant.now(); }
}
