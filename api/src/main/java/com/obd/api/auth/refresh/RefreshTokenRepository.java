package com.obd.api.auth.refresh;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Repository
public class RefreshTokenRepository {

    private final Map<UUID, RefreshTokenDTO> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> hashIndex = new ConcurrentHashMap<>();

    public void save(RefreshTokenDTO token) {
        byId.put(token.id(), token);
        hashIndex.put(token.tokenHash(), token.id());
    }

    public Optional<RefreshTokenDTO> findByHash(String tokenHash) {
        return Optional.ofNullable(hashIndex.get(tokenHash)).map(byId::get);
    }

    public boolean revoke(UUID tokenId, Instant at) {
        var flipped = new boolean[1];
        byId.computeIfPresent(tokenId, (id, existing) -> {
            if (existing.isRevoked()) return existing;
            flipped[0] = true;
            return existing.revoked(at);
        });
        return flipped[0];
    }

    public void revokeFamily(UUID familyId, Instant at) {
        revokeMatching(t -> t.familyId().equals(familyId), at);
    }

    public void revokeAllForUser(UUID userId, Instant at) {
        revokeMatching(t -> t.userId().equals(userId), at);
    }

    public int deleteExpiredBefore(Instant cutoff) {
        var doomed = byId.values().stream()
                .filter(t -> t.expiresAt().isBefore(cutoff)).toList();
        doomed.forEach(t -> { byId.remove(t.id()); hashIndex.remove(t.tokenHash()); });
        return doomed.size();
    }

    private void revokeMatching(Predicate<RefreshTokenDTO> match, Instant at) {
        byId.values().stream()
                .filter(match).filter(t -> !t.isRevoked()).map(RefreshTokenDTO::id).toList()
                .forEach(id -> revoke(id, at));
    }

}
