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

    static RefreshToken from(RefreshTokenDTO r) {
        var e = new RefreshToken();
        e.id = r.id(); e.userId = r.userId(); e.familyId = r.familyId();
        e.tokenHash = r.tokenHash(); e.expiresAt = r.expiresAt(); e.revokedAt = r.revokedAt();
        return e;
    }

    RefreshTokenDTO toRecord() {
        return new RefreshTokenDTO(id, userId, familyId, tokenHash, expiresAt, revokedAt);
    }
}