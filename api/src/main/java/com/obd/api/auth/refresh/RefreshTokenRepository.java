package com.obd.api.auth.refresh;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every mutating method carries its own @Transactional so it commits on its
 * own. RefreshTokenService relies on that: when it detects token reuse it
 * revokes the family and then throws, and the revocation must survive the
 * exception rather than roll back with it.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Conditional update - the {@code revokedAt is null} predicate makes this a
     * compare-and-set in the database, so out of N concurrent callers exactly
     * one gets 1 back and the rest get 0. That is what reuse detection hangs on.
     *
     * @return 1 if this call revoked the token, 0 if it was already revoked
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update RefreshToken t set t.revokedAt = :at
            where t.id = :id and t.revokedAt is null
            """)
    int revoke(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update RefreshToken t set t.revokedAt = :at
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update RefreshToken t set t.revokedAt = :at
            where t.userId = :userId and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
