package com.obd.api.invitecode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {

    Optional<InviteCode> findByInviteCodeHash(String codeHash);

    /** The group's current code, live or not - what the unique index protects. */
    Optional<InviteCode> findByInviteCodeGroupIdAndInviteCodeRevokedAtIsNull(UUID groupId);

    /**
     * Spends one use of a code, if it may still be spent.
     *
     * Every condition is in the WHERE clause, so the check and the write are
     * one statement: two people scanning a single-use QR at the same moment
     * cannot both be let in. A return of 0 means the code is unknown, revoked,
     * expired or exhausted - the service reads the row back to say which.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InviteCode c
               set c.inviteCodeUses = c.inviteCodeUses + 1
             where c.inviteCodeHash = :codeHash
               and c.inviteCodeRevokedAt is null
               and c.inviteCodeExpiresAt > :now
               and (c.inviteCodeMaxUses is null or c.inviteCodeUses < c.inviteCodeMaxUses)
            """)
    int claim(@Param("codeHash") String codeHash, @Param("now") Instant now);

    /**
     * Revokes whatever live code the group has, if any.
     *
     * Deliberately not filtered on expiry: an expired row is still unrevoked,
     * so it still occupies {@code ux_gic_one_live_per_group} and would collide
     * with the next insert. As a JPQL update this runs immediately rather than
     * at flush, which is what keeps it ordered before that insert.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InviteCode c set c.inviteCodeRevokedAt = :now
             where c.inviteCodeGroupId = :groupId and c.inviteCodeRevokedAt is null
            """)
    int revokeLiveFor(@Param("groupId") UUID groupId, @Param("now") Instant now);
}
