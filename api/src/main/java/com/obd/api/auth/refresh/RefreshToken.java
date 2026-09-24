package com.obd.api.auth.refresh;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class RefreshToken {

    // Assigned by issue() rather than generated - the service mints the id
    // alongside the raw token it hands back to the client.
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    static RefreshToken issue(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
        var e = new RefreshToken();
        e.id = UUID.randomUUID();
        e.userId = userId;
        e.familyId = familyId;
        e.tokenHash = tokenHash;
        e.expiresAt = expiresAt;
        return e;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    boolean isUsable() {
        return !isRevoked() && !isExpired();
    }
}
