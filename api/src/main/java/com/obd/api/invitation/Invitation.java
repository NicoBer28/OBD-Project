package com.obd.api.invitation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * An admin asking an email address to join a group. Nothing reaches
 * group_members until the invitee accepts.
 *
 * Keyed by email, not user id: the invitee may not have an account yet. The
 * row waits, and when that email logs in its pending invitations are there.
 */
@Entity
@Table(name = "invitations")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Invitation {

    /** How long an invitation stays acceptable. */
    public static final long TTL_DAYS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "invitation_id")
    private UUID invitationId;

    // Raw ids rather than associations, as Trip does: accepting an invitation
    // needs neither the group's name nor the inviter's.
    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID invitationGroupId;

    /**
     * Must be lowercased before it gets here - users.email is stored that way,
     * and ck_invitations_email_lowercase rejects anything else so a caller
     * that forgets fails loudly instead of creating an invite nobody can find.
     */
    @Column(name = "email", nullable = false, updatable = false)
    private String invitationEmail;

    /** Null once the inviter's account is deleted; the invitation survives. */
    @Column(name = "invited_by", updatable = false)
    private UUID invitationInvitedBy;

    // @Builder ignores plain field initialisers, hence @Builder.Default.
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant invitationCreatedAt = Instant.now();

    /**
     * Defaults to created_at + TTL_DAYS on insert, derived from created_at
     * rather than from a second Instant.now() - two clock reads are never the
     * same instant, and the expiry should be exactly a week from creation.
     * A caller may still set it explicitly (tests do).
     */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant invitationExpiresAt;

    @PrePersist
    void defaultExpiry() {
        if (invitationExpiresAt == null) {
            invitationExpiresAt = invitationCreatedAt.plus(TTL_DAYS, ChronoUnit.DAYS);
        }
    }

    /**
     * Null while pending. Written once, by
     * {@link InvitationRepository#accept} - never through a setter, so the
     * conditions that make acceptance valid cannot be skipped.
     */
    @Column(name = "accepted_at")
    private Instant invitationAcceptedAt;

    /** Derived from the timestamps, so it cannot disagree with them. */
    @Transient
    public InvitationStatus getStatus() {
        return getStatusAt(Instant.now());
    }

    @Transient
    public InvitationStatus getStatusAt(Instant now) {
        if (invitationAcceptedAt != null) {
            return InvitationStatus.ACCEPTED;
        }
        return now.isAfter(invitationExpiresAt) ? InvitationStatus.EXPIRED : InvitationStatus.PENDING;
    }
}
