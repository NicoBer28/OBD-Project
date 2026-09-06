package com.obd.api.auth.refresh;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenDTO(
        UUID id,
        UUID userId,
        UUID familyId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt) {

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }

    public RefreshTokenDTO revoked(Instant at) {
        return new RefreshTokenDTO(id, userId, familyId, tokenHash, expiresAt, at);
    }
}
