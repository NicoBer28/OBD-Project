package com.obd.api.auth.refresh;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final long refreshTtlDays;

    public RefreshTokenService(RefreshTokenRepository repo,
                               @Value("${app.jwt.refresh-ttl-days}") long refreshTtlDays) {
        this.repository = repo;
        this.refreshTtlDays = refreshTtlDays;
    }

    public Duration ttl() { return Duration.ofDays(refreshTtlDays); }

    public String issueNewFamily(UUID userId) {
        return persist(userId, UUID.randomUUID());
    }

    public Rotation rotate(String rawToken) {
        String hash = sha256(rawToken);
        Instant now = Instant.now();

        RefreshTokenDTO token = repository.findByHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (token.isExpired()) {
            throw new BadCredentialsException("Refresh token expired");
        }

        if (!repository.revoke(token.id(), now)) {
            repository.revokeFamily(token.familyId(), now);
            throw new BadCredentialsException("Refresh token reuse detected");
        }

        String next = persist(token.userId(), token.familyId());   // same family
        return new Rotation(token.userId(), next);
    }
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    @Scheduled(fixedRateString = "${app.jwt.refresh-cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        int removed = repository.deleteExpiredBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired refresh token(s)", removed);
        }
    }

    private String persist(UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        repository.save(new RefreshTokenDTO(
                UUID.randomUUID(), userId, familyId, sha256(raw),
                Instant.now().plus(ttl()), null));

        return raw;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Rotation(UUID userId, String newRawToken) {}
}
