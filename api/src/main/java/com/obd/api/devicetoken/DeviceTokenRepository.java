package com.obd.api.devicetoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /** The authentication path: hash in, row out. */
    Optional<DeviceToken> findByDeviceTokenHash(String deviceTokenHash);

    /** What the app lists: "these phones can upload for this car". */
    List<DeviceToken> findByDeviceTokenUserIdAndDeviceTokenCarIdAndDeviceTokenRevokedAtIsNullOrderByDeviceTokenCreatedAtDesc(
            UUID deviceTokenUserId, UUID deviceTokenCarId);

    /**
     * Revokes one token, if it is this user's and still live.
     *
     * Ownership is in the WHERE clause, so revoking somebody else's token
     * cannot happen even by accident, and the service learns it affected
     * nothing rather than having to check first.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeviceToken t set t.deviceTokenRevokedAt = :now
             where t.deviceTokenId = :tokenId
               and t.deviceTokenUserId = :userId
               and t.deviceTokenRevokedAt is null
            """)
    int revoke(@Param("tokenId") UUID tokenId, @Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Revokes every live token of an account - what a password change and a
     * password reset do.
     *
     * A changed password means "I think somebody else has my phone", so the
     * credential sitting on that phone has to die with the session. The app
     * notices the 401 on its next upload and mints a new one.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeviceToken t set t.deviceTokenRevokedAt = :now
             where t.deviceTokenUserId = :userId and t.deviceTokenRevokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Stamps last use. Separate from the read so authentication stays one
     * SELECT, and called only when the stored value is actually stale - a
     * write on every telemetry upload would be pure cost.
     */
    // Transactional in its own right: the only caller is DeviceAuthFilter,
    // which cannot be transactional itself (annotating a filter proxies it and
    // breaks Spring's own logger field).
    @Modifying
    @Transactional
    @Query("""
            update DeviceToken t set t.deviceTokenLastUsedAt = :now
             where t.deviceTokenId = :tokenId
               and (t.deviceTokenLastUsedAt is null or t.deviceTokenLastUsedAt < :staleBefore)
            """)
    int touch(@Param("tokenId") UUID tokenId,
              @Param("now") Instant now,
              @Param("staleBefore") Instant staleBefore);
}
