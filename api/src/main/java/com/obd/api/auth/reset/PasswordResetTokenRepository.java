package com.obd.api.auth.reset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    Optional<PasswordResetToken> findByTokenUserIdAndTokenConsumedAtIsNull(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.tokenConsumedAt = :now
             where t.tokenHash = :tokenHash
               and t.tokenConsumedAt is null
               and t.tokenExpiresAt > :now
            """)
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.tokenConsumedAt = :now
             where t.tokenUserId = :userId and t.tokenConsumedAt is null
            """)
    int consumeLiveFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
