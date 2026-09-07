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

    /**
     * Deliberately not @Transactional. The reuse branch revokes the family and
     * then throws; wrapping the whole method in one transaction would roll that
     * revocation straight back and leave a compromised family alive. Each
     * repository call commits on its own instead. The cost is that a crash
     * between revoking the old token and persisting the new one loses the
     * session - a fail-closed outcome, so the user just logs in again.
     */
    public Rotation rotate(String rawToken) {
        String hash = sha256(rawToken);
        Instant now = Instant.now();

        RefreshToken token = repository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (token.isExpired()) {
            throw new BadCredentialsException("Refresh token expired");
        }

        // 0 rows updated means the token was already revoked, i.e. this is a
        // replay of a token that was rotated out - burn the whole family.
        if (repository.revoke(token.getId(), now) == 0) {
            repository.revokeFamily(token.getFamilyId(), now);
            throw new BadCredentialsException("Refresh token reuse detected");
        }

        String next = persist(token.getUserId(), token.getFamilyId());   // same family
        return new Rotation(token.getUserId(), next);
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

        repository.save(RefreshToken.issue(
                userId, familyId, sha256(raw), Instant.now().plus(ttl())));

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
