package com.obd.api.invitation;

import com.obd.api.invitation.dto.InvitationDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /**
     * Marks an invitation accepted, if - and only if - it is the given
     * invitation, addressed to the given email, still pending, and not yet
     * expired. Returns 1 if it was accepted now, 0 otherwise.
     *
     * Every condition is in the WHERE clause so the whole check-and-write is
     * one atomic statement. The alternative - load, inspect in Java, set
     * accepted_at, save - has two holes: a double tap on "accept" (or a race
     * with revoke) can both pass the check, and nothing forces the caller to
     * check the email at all. Here the caller physically cannot accept an
     * invitation that is not theirs, or twice, whatever it does around this
     * call. Same compare-and-set as CarRepository.refreshSnapshot.
     *
     * A 0 means one of four things; the service that wants to tell the caller
     * which one can findById afterwards. The write itself is safe either way.
     *
     * {@code email} must be lowercased, as users.email already is. {@code now}
     * is a parameter rather than CURRENT_TIMESTAMP so tests can pin it and so
     * the service uses the same instant it writes to group_members.
     *
     * A JPQL update bypasses the persistence context, hence the flush/clear
     * flags: without them an Invitation loaded earlier in the transaction
     * would still show accepted_at = null.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i
               set i.invitationAcceptedAt = :now
             where i.invitationId = :invitationId
               and i.invitationEmail = :email
               and i.invitationAcceptedAt is null
               and i.invitationExpiresAt > :now
            """)
    int accept(@Param("invitationId") UUID invitationId,
               @Param("email") String email,
               @Param("now") Instant now);

    @Query("""
        select new com.obd.api.invitation.dto.InvitationDTO$Pending(
                   i.invitationId, g.groupId, g.groupName,
                   i.invitationCreatedAt, i.invitationExpiresAt)
          from Invitation i, Group g
         where g.groupId = i.invitationGroupId
           and i.invitationEmail = :email
           and i.invitationAcceptedAt is null
           and i.invitationExpiresAt > :now
         order by i.invitationCreatedAt desc
        """)
    List<InvitationDTO.Pending> findPendingFor(@Param("email") String email, @Param("now") Instant now);


}
